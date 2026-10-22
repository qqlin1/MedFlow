package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.shared.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same-key concurrent creation against a disposable local MySQL instance.
 * Run with tools/test-confirmation.ps1 -TestSelection AppointmentIdempotencyMySqlIT.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class AppointmentIdempotencyMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final int CONTENDERS = 32;
    private static final int CAPACITY = 10;
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s]*)?$"
    );
    private static final String TEST_DATABASE_URL =
            requireIsolatedDatabaseUrl();

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long ownerUserId;
    private long patientId;
    private long otherPatientId;
    private long slotId;

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 30");
        registry.add("medflow.security.jwt.secret",
                () -> TEST_ONLY_JWT_SECRET);
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @BeforeEach
    void setUpFixture() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(TEST_SCHEMA);
        cleanMutableTestData();

        ownerUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?", "patient.li");
        long doctorUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?", "doctor.wang");
        long templateId = requiredLong(
                "SELECT id FROM med_shift_template WHERE code = 'MORNING'");
        String suffix = UUID.randomUUID().toString().replace("-", "");

        long departmentId = insertAndGetId("""
                INSERT INTO med_department (name, status)
                VALUES (?, 'ENABLED')
                """, "idempotency-department-" + suffix);
        long doctorId = insertAndGetId("""
                INSERT INTO med_doctor (user_id, department_id, name, status)
                VALUES (?, ?, ?, 'ENABLED')
                """, doctorUserId, departmentId,
                "idempotency-doctor-" + suffix);
        long roomId = insertAndGetId("""
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, "idempotency-room-" + suffix);
        long scheduleId = insertAndGetId("""
                INSERT INTO med_schedule (
                    doctor_id, clinic_room_id, shift_template_id,
                    work_date, start_time, end_time,
                    slot_duration_minutes, capacity_per_slot, status
                )
                VALUES (?, ?, ?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                        '09:00:00', '09:30:00', 30, ?, 'PUBLISHED')
                """, doctorId, roomId, templateId, CAPACITY);
        slotId = insertAndGetId("""
                INSERT INTO med_slot (
                    schedule_id, work_date, start_time, end_time,
                    booking_deadline, total_capacity,
                    remaining_capacity, status
                )
                VALUES (?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                        '09:00:00', '09:30:00',
                        DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),
                        ?, ?, 'OPEN')
                """, scheduleId, CAPACITY, CAPACITY);
        patientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date,
                    phone, relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01',
                        '13800138000', 'SELF')
                """, ownerUserId, "idempotency-patient-" + suffix);
        otherPatientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date,
                    phone, relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01',
                        '13800138001', 'FAMILY')
                """, ownerUserId, "idempotency-other-" + suffix);
        assertThat(requiredInt(
                "SELECT remaining_capacity FROM med_slot WHERE id = ?", slotId))
                .isEqualTo(CAPACITY);
    }

    @AfterEach
    void tearDownFixture() {
        cleanMutableTestData();
    }

    @Test
    void sameKeyConcurrentRequestsReplayOneFrozenResult()
            throws Exception {
        String key = "same-key-" + UUID.randomUUID().toString().replace("-", "");
        CreateAppointmentCommand command =
                new CreateAppointmentCommand(patientId, slotId, key);
        RunResult run = runConcurrentAttempts(command);
        List<AppointmentCreationResult> successes = run.attempts().stream()
                .filter(attempt -> attempt.result() != null)
                .map(Attempt::result).toList();
        List<String> unexpected = run.attempts().stream()
                .filter(attempt -> attempt.failure() != null)
                .map(Attempt::failure).toList();
        AppointmentCreationResult first =
                successes.isEmpty() ? null : successes.getFirst();
        DatabaseSnapshot afterConcurrent = databaseSnapshot(key);
        IdempotencyRecordSnapshot record = idempotencyRecord(key);
        AppointmentCreationResult frozenResult = null;
        String frozenResultFailure = null;
        if (record != null && record.resultJson() != null) {
            try {
                frozenResult = objectMapper.readValue(
                        record.resultJson(), AppointmentCreationResult.class);
            } catch (Exception exception) {
                frozenResultFailure = describe(exception);
            }
        }

        AppointmentCreationResult replay = null;
        String replayFailure = null;
        String changedBodyCode = null;
        Integer changedBodyHttpStatus = null;
        String changedBodyFailure = null;
        if (first != null) {
            try {
                replay = appointmentService.create(ownerUserId, command);
            } catch (Throwable throwable) {
                replayFailure = describe(throwable);
            }
            try {
                appointmentService.create(ownerUserId,
                        new CreateAppointmentCommand(
                                otherPatientId, slotId, key));
                changedBodyFailure = "different body unexpectedly succeeded";
            } catch (BusinessException exception) {
                changedBodyCode = exception.getErrorCode().getCode();
                changedBodyHttpStatus =
                        exception.getErrorCode().getHttpStatus().value();
            } catch (Throwable throwable) {
                changedBodyFailure = describe(throwable);
            }
        }
        DatabaseSnapshot afterReplayAndConflict = databaseSnapshot(key);

        writeReport(key, run, successes, unexpected, first,
                afterConcurrent, afterReplayAndConflict, record,
                frozenResult, frozenResultFailure, replay, replayFailure,
                changedBodyCode, changedBodyHttpStatus, changedBodyFailure);

        assertThat(run.attempts()).hasSize(CONTENDERS);
        assertThat(unexpected).isEmpty();
        assertThat(successes).hasSize(CONTENDERS);
        assertThat(new HashSet<>(successes)).hasSize(1);
        assertThat(first).isNotNull();
        assertThat(afterConcurrent.remainingCapacity())
                .isEqualTo(CAPACITY - 1);
        assertThat(afterConcurrent.appointments()).isEqualTo(1);
        assertThat(afterConcurrent.activeAppointments()).isEqualTo(1);
        assertThat(afterConcurrent.correctAppointments()).isEqualTo(1);
        assertThat(afterConcurrent.historyRows()).isEqualTo(1);
        assertThat(afterConcurrent.correctCreatedHistoryRows()).isEqualTo(1);
        assertThat(afterConcurrent.idempotencyRows()).isEqualTo(1);
        assertThat(afterConcurrent.successRecords()).isEqualTo(1);
        assertThat(afterConcurrent.processingRecords()).isZero();
        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.appointmentId()).isEqualTo(first.appointmentId());
        assertThat(frozenResultFailure).isNull();
        assertThat(frozenResult).isEqualTo(first);
        assertThat(replayFailure).isNull();
        assertThat(replay).isEqualTo(first);
        assertThat(changedBodyFailure).isNull();
        assertThat(changedBodyCode).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(changedBodyHttpStatus).isEqualTo(409);
        assertThat(afterReplayAndConflict).isEqualTo(afterConcurrent);
    }

    private RunResult runConcurrentAttempts(CreateAppointmentCommand command)
            throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>(CONTENDERS);
        try {
            for (int index = 0; index < CONTENDERS; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long begin = System.nanoTime();
                    try {
                        return Attempt.success(
                                appointmentService.create(ownerUserId, command),
                                elapsedMillis(begin));
                    } catch (BusinessException exception) {
                        return Attempt.failure(
                                "BusinessException: "
                                        + exception.getErrorCode().getCode(),
                                elapsedMillis(begin));
                    } catch (Throwable throwable) {
                        return Attempt.failure(
                                describe(throwable), elapsedMillis(begin));
                    }
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS))
                    .as("all 32 virtual threads reached the start gate")
                    .isTrue();
            long begin = System.nanoTime();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            List<Attempt> attempts = new ArrayList<>(CONTENDERS);
            for (Future<Attempt> future : futures) {
                try {
                    long waitNanos = Math.max(1, deadline - System.nanoTime());
                    attempts.add(future.get(waitNanos, TimeUnit.NANOSECONDS));
                } catch (ExecutionException exception) {
                    attempts.add(Attempt.failure(
                            describe(exception.getCause()), 0));
                } catch (TimeoutException exception) {
                    future.cancel(true);
                    attempts.add(Attempt.failure("attempt timed out", 0));
                }
            }
            return new RunResult(attempts, elapsedMillis(begin));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private DatabaseSnapshot databaseSnapshot(String key) {
        return new DatabaseSnapshot(
                requiredInt("SELECT remaining_capacity FROM med_slot WHERE id = ?",
                        slotId),
                requiredInt("SELECT COUNT(*) FROM med_appointment WHERE slot_id = ?",
                        slotId),
                requiredInt("""
                        SELECT COUNT(*) FROM med_appointment
                        WHERE slot_id = ? AND status IN ('PENDING_CONFIRMATION', 'BOOKED')
                        """, slotId),
                requiredInt("""
                        SELECT COUNT(*) FROM med_appointment
                        WHERE slot_id = ? AND patient_id = ?
                          AND status = 'PENDING_CONFIRMATION'
                        """, slotId, patientId),
                requiredInt("""
                        SELECT COUNT(*) FROM med_appointment_status_log history
                        JOIN med_appointment appointment
                          ON appointment.id = history.appointment_id
                        WHERE appointment.slot_id = ?
                        """, slotId),
                requiredInt("""
                        SELECT COUNT(*) FROM med_appointment_status_log history
                        JOIN med_appointment appointment
                          ON appointment.id = history.appointment_id
                        WHERE appointment.slot_id = ?
                          AND history.from_status IS NULL
                          AND history.to_status = 'PENDING_CONFIRMATION'
                          AND history.event_type = 'CREATED'
                          AND history.actor_user_id = ?
                        """, slotId, ownerUserId),
                requiredInt("""
                        SELECT COUNT(*) FROM sys_idempotency_record
                        WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                          AND idempotency_key = ?
                        """, ownerUserId, key),
                requiredInt("""
                        SELECT COUNT(*) FROM sys_idempotency_record
                        WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                          AND idempotency_key = ? AND status = 'SUCCESS'
                        """, ownerUserId, key),
                requiredInt("""
                        SELECT COUNT(*) FROM sys_idempotency_record
                        WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                          AND idempotency_key = ? AND status = 'PROCESSING'
                        """, ownerUserId, key)
        );
    }

    private IdempotencyRecordSnapshot idempotencyRecord(String key) {
        List<IdempotencyRecordSnapshot> records = jdbcTemplate.query("""
                SELECT status, appointment_id,
                       CAST(result_json AS CHAR) AS result_json
                FROM sys_idempotency_record
                WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                  AND idempotency_key = ?
                """, (rs, rowNum) -> new IdempotencyRecordSnapshot(
                rs.getString("status"),
                rs.getObject("appointment_id", Long.class),
                rs.getString("result_json")), ownerUserId, key);
        return records.isEmpty() ? null : records.getFirst();
    }

    private void writeReport(
            String key,
            RunResult run,
            List<AppointmentCreationResult> successes,
            List<String> unexpected,
            AppointmentCreationResult first,
            DatabaseSnapshot afterConcurrent,
            DatabaseSnapshot afterReplayAndConflict,
            IdempotencyRecordSnapshot record,
            AppointmentCreationResult frozenResult,
            String frozenResultFailure,
            AppointmentCreationResult replay,
            String replayFailure,
            String changedBodyCode,
            Integer changedBodyHttpStatus,
            String changedBodyFailure
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestampUtc", Instant.now().toString());
        report.put("scope", "service-level same-key concurrent invocation; not HTTP throughput");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("mysqlVersion", jdbcTemplate.queryForObject(
                "SELECT VERSION()", String.class));
        report.put("databaseSchema", TEST_SCHEMA);
        report.put("workerModel", "32 virtual threads with one start gate");
        report.put("hikariMaximumPoolSize", CONTENDERS);
        report.put("hikariConnectionTimeoutMillis", 30000);
        report.put("innodbLockWaitTimeoutSeconds", 30);
        report.put("idempotencyKey", key);
        report.put("contenders", CONTENDERS);
        report.put("elapsedMillis", run.elapsedMillis());
        report.put("completed", run.attempts().size());
        report.put("successes", successes.size());
        report.put("distinctResults", new HashSet<>(successes).size());
        report.put("failures", unexpected);
        report.put("firstResult", first);
        report.put("afterConcurrent", afterConcurrent);
        report.put("idempotencyRecord", record);
        report.put("frozenResult", frozenResult);
        report.put("frozenResultFailure", frozenResultFailure);
        report.put("sequentialReplay", replay);
        report.put("sequentialReplayFailure", replayFailure);
        report.put("differentBodyCode", changedBodyCode);
        report.put("differentBodyHttpStatus", changedBodyHttpStatus);
        report.put("differentBodyFailure", changedBodyFailure);
        report.put("afterReplayAndConflict", afterReplayAndConflict);
        report.put("individualLatenciesMillis", run.attempts().stream()
                .map(Attempt::latencyMillis).toList());
        Path reportPath = Path.of("target", "idempotency-test", "latest.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), report);
    }

    private void cleanMutableTestData() {
        jdbcTemplate.update("DELETE FROM med_appointment_status_log");
        jdbcTemplate.update("DELETE FROM sys_idempotency_record");
        jdbcTemplate.update("DELETE FROM med_appointment");
        jdbcTemplate.update("DELETE FROM med_slot");
        jdbcTemplate.update("DELETE FROM med_schedule");
        jdbcTemplate.update("DELETE FROM med_patient");
        jdbcTemplate.update("DELETE FROM med_doctor");
        jdbcTemplate.update("DELETE FROM med_clinic_room");
        jdbcTemplate.update("DELETE FROM med_department");
    }

    private long insertAndGetId(String sql, Object... arguments) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int affected = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, keyHolder);
        assertThat(affected).isEqualTo(1);
        Number generatedId = keyHolder.getKey();
        assertThat(generatedId).isNotNull();
        return generatedId.longValue();
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private int requiredInt(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private static long elapsedMillis(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + ": "
                + (message == null ? "" : message.substring(
                        0, Math.min(300, message.length())));
    }

    private static String requireIsolatedDatabaseUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher matcher = url == null
                ? null : ISOLATED_URL_PATTERN.matcher(url);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must point only to "
                            + "jdbc:mysql://127.0.0.1:<random-port>/"
                            + TEST_SCHEMA);
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must use a "
                            + "non-default temporary MySQL port");
        }
        return url;
    }

    private record Attempt(
            AppointmentCreationResult result,
            String failure,
            long latencyMillis
    ) {
        private static Attempt success(
                AppointmentCreationResult result, long latencyMillis
        ) {
            return new Attempt(result, null, latencyMillis);
        }

        private static Attempt failure(String failure, long latencyMillis) {
            return new Attempt(null, failure, latencyMillis);
        }
    }

    private record RunResult(List<Attempt> attempts, long elapsedMillis) {
    }

    private record DatabaseSnapshot(
            int remainingCapacity,
            int appointments,
            int activeAppointments,
            int correctAppointments,
            int historyRows,
            int correctCreatedHistoryRows,
            int idempotencyRows,
            int successRecords,
            int processingRecords
    ) {
    }

    private record IdempotencyRecordSnapshot(
            String status,
            Long appointmentId,
            String resultJson
    ) {
    }
}
