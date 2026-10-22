package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * Service-level contention experiment against a disposable MySQL schema.
 * Run with tools/test-confirmation.ps1 -TestSelection AppointmentCapacityMySqlIT.
 * It deliberately refuses the normal MedFlow datasource.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class AppointmentCapacityMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final int CONTENDERS = 500;
    private static final int CAPACITY = 50;
    private static final int POOL_SIZE = 32;
    /* Base64 for exactly 32 test-only bytes; never a production secret. */
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
    private long slotId;
    private final List<Long> patientIds = new ArrayList<>(CONTENDERS);

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size",
                () -> Integer.toString(POOL_SIZE));
        registry.add("spring.datasource.hikari.connection-timeout",
                () -> "30000");
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
                """, "capacity-department-" + suffix);
        long doctorId = insertAndGetId("""
                INSERT INTO med_doctor (user_id, department_id, name, status)
                VALUES (?, ?, ?, 'ENABLED')
                """, doctorUserId, departmentId, "capacity-doctor-" + suffix);
        long roomId = insertAndGetId("""
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, "capacity-room-" + suffix);
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

        patientIds.clear();
        for (int index = 0; index < CONTENDERS; index++) {
            patientIds.add(insertAndGetId("""
                    INSERT INTO med_patient (
                        owner_user_id, name, gender, birth_date,
                        phone, relationship
                    )
                    VALUES (?, ?, 'UNKNOWN', '2000-01-01',
                            '13800138000', 'FAMILY')
                    """, ownerUserId, "capacity-patient-" + index));
        }
        assertThat(patientIds).doesNotHaveDuplicates().hasSize(CONTENDERS);
    }

    @AfterEach
    void tearDownFixture() {
        cleanMutableTestData();
    }

    @Test
    void fiveHundredDistinctPatientsCompeteForFiftyPlaces()
            throws Exception {
        RunResult run = runConcurrentAttempts();
        List<Attempt> attempts = run.attempts();
        Map<String, Long> failuresByCode = new TreeMap<>();
        List<String> unexpectedSamples = new ArrayList<>();
        int successes = 0;
        int expectedConflicts = 0;
        List<Long> latenciesMillis = new ArrayList<>(attempts.size());

        for (Attempt attempt : attempts) {
            latenciesMillis.add(attempt.latencyMillis());
            if (attempt.success()) {
                successes++;
            } else if (attempt.failureCode() != null) {
                failuresByCode.merge(attempt.failureCode(), 1L, Long::sum);
                if (attempt.expectedConflict()) {
                    expectedConflicts++;
                } else if (unexpectedSamples.size() < 8) {
                    unexpectedSamples.add("business: " + attempt.failureCode());
                }
            } else if (unexpectedSamples.size() < 8) {
                unexpectedSamples.add(attempt.unexpected());
            }
        }

        int remaining = requiredInt(
                "SELECT remaining_capacity FROM med_slot WHERE id = ?", slotId);
        int activeAppointments = requiredInt("""
                SELECT COUNT(*) FROM med_appointment
                WHERE slot_id = ? AND status IN ('PENDING_CONFIRMATION', 'BOOKED')
                """, slotId);
        int allAppointments = requiredInt(
                "SELECT COUNT(*) FROM med_appointment WHERE slot_id = ?", slotId);
        int distinctBookedPatients = requiredInt("""
                SELECT COUNT(DISTINCT patient_id) FROM med_appointment
                WHERE slot_id = ?
                """, slotId);
        int creationLogs = requiredInt("""
                SELECT COUNT(*)
                FROM med_appointment_status_log history
                JOIN med_appointment appointment
                  ON appointment.id = history.appointment_id
                WHERE appointment.slot_id = ? AND history.event_type = 'CREATED'
                """, slotId);
        int successfulIdempotencyRecords = requiredInt("""
                SELECT COUNT(*) FROM sys_idempotency_record
                WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                  AND idempotency_key LIKE ? AND status = 'SUCCESS'
                """, ownerUserId, run.keyPrefix() + "%");
        int processingIdempotencyRecords = requiredInt("""
                SELECT COUNT(*) FROM sys_idempotency_record
                WHERE user_id = ? AND operation_type = 'CREATE_APPOINTMENT'
                  AND idempotency_key LIKE ? AND status = 'PROCESSING'
                """, ownerUserId, run.keyPrefix() + "%");
        int appointmentsWithOneCorrectCreationLog = requiredInt("""
                SELECT COUNT(*) FROM (
                    SELECT appointment.id
                    FROM med_appointment appointment
                    LEFT JOIN med_appointment_status_log history
                      ON history.appointment_id = appointment.id
                    WHERE appointment.slot_id = ?
                    GROUP BY appointment.id
                    HAVING COUNT(history.id) = 1
                       AND SUM(CASE
                           WHEN history.event_type = 'CREATED'
                            AND history.from_status IS NULL
                            AND history.to_status = 'PENDING_CONFIRMATION'
                            AND history.actor_user_id = ?
                           THEN 1 ELSE 0 END) = 1
                ) correct_history
                """, slotId, ownerUserId);
        List<Long> persistedAppointmentIds = jdbcTemplate.queryForList(
                "SELECT id FROM med_appointment WHERE slot_id = ?",
                Long.class, slotId);
        List<Long> persistedPatientIds = jdbcTemplate.queryForList(
                "SELECT patient_id FROM med_appointment WHERE slot_id = ?",
                Long.class, slotId);
        List<Long> returnedAppointmentIds = attempts.stream()
                .filter(Attempt::success).map(Attempt::appointmentId).toList();
        List<Long> winningPatientIds = attempts.stream()
                .filter(Attempt::success).map(Attempt::patientId).toList();

        writeReport(run, latenciesMillis, failuresByCode,
                unexpectedSamples, successes, expectedConflicts, remaining,
                activeAppointments, allAppointments, distinctBookedPatients,
                creationLogs, successfulIdempotencyRecords,
                processingIdempotencyRecords,
                appointmentsWithOneCorrectCreationLog,
                new HashSet<>(returnedAppointmentIds).size(),
                new HashSet<>(winningPatientIds).size());

        assertThat(attempts).hasSize(CONTENDERS);
        assertThat(successes).isEqualTo(CAPACITY);
        assertThat(expectedConflicts).isEqualTo(CONTENDERS - CAPACITY);
        assertThat(unexpectedSamples).isEmpty();
        assertThat(remaining).isZero();
        assertThat(activeAppointments).isEqualTo(CAPACITY);
        assertThat(allAppointments).isEqualTo(CAPACITY);
        assertThat(distinctBookedPatients).isEqualTo(CAPACITY);
        assertThat(creationLogs).isEqualTo(CAPACITY);
        assertThat(successfulIdempotencyRecords).isEqualTo(CAPACITY);
        assertThat(processingIdempotencyRecords).isZero();
        assertThat(appointmentsWithOneCorrectCreationLog)
                .isEqualTo(CAPACITY);
        assertThat(returnedAppointmentIds)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(persistedAppointmentIds);
        assertThat(winningPatientIds)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(persistedPatientIds);
    }

    private RunResult runConcurrentAttempts() throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>(CONTENDERS);
        String runId = UUID.randomUUID().toString().replace("-", "");
        String keyPrefix = "capacity-" + runId + "-";

        try {
            for (int index = 0; index < CONTENDERS; index++) {
                long patientId = patientIds.get(index);
                String key = keyPrefix + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long begin = System.nanoTime();
                    try {
                        long appointmentId = appointmentService.create(ownerUserId,
                                new CreateAppointmentCommand(
                                        patientId, slotId, key)).appointmentId();
                        return Attempt.success(
                                appointmentId, patientId, elapsedMillis(begin));
                    } catch (BusinessException exception) {
                        String code = exception.getErrorCode().getCode();
                        boolean expected = exception.getErrorCode()
                                .getHttpStatus().value() == 409
                                && ("SLOT_SOLD_OUT".equals(code)
                                || "SLOT_NOT_BOOKABLE".equals(code));
                        return Attempt.businessFailure(
                                code, expected, elapsedMillis(begin));
                    } catch (Throwable throwable) {
                        return Attempt.unexpected(
                                describe(throwable), elapsedMillis(begin));
                    }
                }));
            }

            assertThat(ready.await(20, TimeUnit.SECONDS))
                    .as("all 500 virtual threads reached the start gate")
                    .isTrue();
            long competitionBegin = System.nanoTime();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            List<Attempt> attempts = new ArrayList<>(CONTENDERS);
            for (Future<Attempt> future : futures) {
                try {
                    long waitNanos = Math.max(1, deadline - System.nanoTime());
                    attempts.add(future.get(waitNanos, TimeUnit.NANOSECONDS));
                } catch (ExecutionException exception) {
                    attempts.add(Attempt.unexpected(
                            describe(exception.getCause()), 0));
                } catch (TimeoutException exception) {
                    future.cancel(true);
                    attempts.add(Attempt.unexpected("attempt timed out", 0));
                }
            }
            return new RunResult(
                    attempts, keyPrefix, elapsedMillis(competitionBegin));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private void writeReport(
            RunResult run,
            List<Long> latenciesMillis,
            Map<String, Long> failuresByCode,
            List<String> unexpectedSamples,
            int successes,
            int expectedConflicts,
            int remaining,
            int activeAppointments,
            int allAppointments,
            int distinctBookedPatients,
            int creationLogs,
            int successfulIdempotencyRecords,
            int processingIdempotencyRecords,
            int appointmentsWithOneCorrectCreationLog,
            int distinctReturnedAppointmentIds,
            int distinctWinningPatientIds
    ) throws Exception {
        List<Attempt> attempts = run.attempts();
        List<Long> sortedLatencies = latenciesMillis.stream()
                .sorted(Comparator.naturalOrder()).toList();
        long p95Millis = sortedLatencies.get(
                (int) Math.ceil(sortedLatencies.size() * 0.95) - 1);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestampUtc", Instant.now().toString());
        report.put("scope", "service-level concurrent invocation; not HTTP throughput");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("mysqlVersion", jdbcTemplate.queryForObject(
                "SELECT VERSION()", String.class));
        report.put("databaseSchema", TEST_SCHEMA);
        report.put("contenders", CONTENDERS);
        report.put("capacity", CAPACITY);
        report.put("workerModel", "500 virtual threads with one start gate");
        report.put("hikariMaximumPoolSize", POOL_SIZE);
        report.put("hikariConnectionTimeoutMillis", 30000);
        report.put("idempotencyKeyPrefix", run.keyPrefix());
        report.put("elapsedMillis", run.elapsedMillis());
        report.put("attemptsCompleted", attempts.size());
        report.put("successes", successes);
        report.put("expectedBusinessConflicts", expectedConflicts);
        report.put("failuresByCode", failuresByCode);
        report.put("unexpectedCount", attempts.size() - successes - expectedConflicts);
        report.put("unexpectedSamples", unexpectedSamples);
        report.put("remainingCapacity", remaining);
        report.put("activeAppointments", activeAppointments);
        report.put("allAppointments", allAppointments);
        report.put("distinctBookedPatients", distinctBookedPatients);
        report.put("creationLogs", creationLogs);
        report.put("successfulIdempotencyRecords", successfulIdempotencyRecords);
        report.put("processingIdempotencyRecords", processingIdempotencyRecords);
        report.put("appointmentsWithOneCorrectCreationLog",
                appointmentsWithOneCorrectCreationLog);
        report.put("distinctReturnedAppointmentIds", distinctReturnedAppointmentIds);
        report.put("distinctWinningPatientIds", distinctWinningPatientIds);
        report.put("p95ServiceCallMillis", p95Millis);
        report.put("maxServiceCallMillis", sortedLatencies.getLast());
        Path reportPath = Path.of("target", "capacity-test", "latest.json");
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
        Integer value = jdbcTemplate.queryForObject(
                sql, Integer.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private static long elapsedMillis(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + ": "
                + (message == null ? "" : message.substring(
                        0, Math.min(200, message.length())));
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

    private record RunResult(
            List<Attempt> attempts, String keyPrefix, long elapsedMillis
    ) {
    }

    private record Attempt(
            boolean success,
            String failureCode,
            boolean expectedConflict,
            String unexpected,
            Long appointmentId,
            Long patientId,
            long latencyMillis
    ) {
        private static Attempt success(
                long appointmentId, long patientId, long latencyMillis
        ) {
            return new Attempt(true, null, false, null,
                    appointmentId, patientId, latencyMillis);
        }

        private static Attempt businessFailure(
                String code, boolean expectedConflict, long latencyMillis
        ) {
            return new Attempt(false, code, expectedConflict, null, null, null,
                    latencyMillis);
        }

        private static Attempt unexpected(
                String detail, long latencyMillis
        ) {
            return new Attempt(false, null, false, detail, null, null,
                    latencyMillis);
        }
    }
}
