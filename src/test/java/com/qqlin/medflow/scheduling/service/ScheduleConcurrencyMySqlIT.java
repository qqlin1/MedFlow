package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.ScheduleDraft;
import com.qqlin.medflow.scheduling.repository.ScheduleRepository;
import com.qqlin.medflow.scheduling.repository.ShiftTemplateRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Time;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Real-MySQL proof that schedule ownership locks and overlap reads form one
 * concurrency protocol.
 *
 * <p>The spy coordination pauses a real {@link ScheduleService} transaction
 * after it owns both resource rows and just before its real insert. A second
 * real service transaction then reads its shift template, establishing an
 * earlier consistent read, and is observed waiting for the shared
 * doctor or clinic-room row. Once released, its overlap check must use current
 * data. The original RR/plain-read implementation committed both different-start
 * schedules. The fixed service uses RC and locking overlap reads.</p>
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest
class ScheduleConcurrencyMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final String TEMPLATE_CODE_PREFIX = "TEST_SCHEDULE_RACE_";
    private static final String TEST_USER_PREFIX = "schedule.concurrent.";
    private static final Duration SHORT_WAIT = Duration.ofSeconds(8);
    private static final Duration FUTURE_WAIT = Duration.ofSeconds(15);
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s]*)?$"
    );
    private static final String TEST_DATABASE_URL =
            requireIsolatedDatabaseUrl();
    /* Base64 for exactly 32 test-only bytes; never a production secret. */
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ShiftTemplateRepository shiftTemplateRepository;

    @MockitoSpyBean
    private ScheduleRepository scheduleRepository;

    private LocalDate workDate;
    private long doctorOneId;
    private long doctorTwoId;
    private long roomOneId;
    private long roomTwoId;

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("spring.datasource.hikari.transaction-isolation",
                () -> "TRANSACTION_REPEATABLE_READ");
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 12");
        registry.add("medflow.appointment.expiration.enabled", () -> "false");
        registry.add("medflow.security.jwt.secret",
                () -> TEST_ONLY_JWT_SECRET);
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @BeforeEach
    void setUpFixture() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class)).isEqualTo(TEST_SCHEMA);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT @@transaction_isolation", String.class))
                .isEqualToIgnoringCase("REPEATABLE-READ");
        cleanMutableTestData();

        workDate = LocalDate.now().plusDays(7);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long departmentId = insertAndGetId("""
                INSERT INTO med_department (name, status)
                VALUES (?, 'ENABLED')
                """, "schedule-race-department-" + suffix);
        long firstDoctorUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?", "doctor.wang");
        long secondDoctorUserId = insertAndGetId("""
                INSERT INTO sys_user (
                    username, password_hash, role, status, token_version
                )
                VALUES (?, 'test-only-not-used-for-login',
                        'DOCTOR', 'ENABLED', 0)
                """, TEST_USER_PREFIX + suffix);

        doctorOneId = insertDoctor(
                firstDoctorUserId, departmentId, "schedule-race-doctor-one");
        doctorTwoId = insertDoctor(
                secondDoctorUserId, departmentId, "schedule-race-doctor-two");
        roomOneId = insertRoom(
                departmentId, "schedule-race-room-one");
        roomTwoId = insertRoom(
                departmentId, "schedule-race-room-two");
    }

    @AfterEach
    void tearDownFixture() {
        cleanMutableTestData();
    }

    @Test
    void olderSnapshotMustRejectDifferentStartOverlapForSameDoctor()
            throws Exception {
        long firstTemplateId = insertTemplate(
                LocalTime.of(9, 0), LocalTime.of(11, 0));
        long secondTemplateId = insertTemplate(
                LocalTime.of(10, 0), LocalTime.of(12, 0));

        SnapshotRaceResult race = runOlderSnapshotRace(
                new CreateScheduleCommand(
                        doctorOneId, roomOneId, firstTemplateId, workDate),
                new CreateScheduleCommand(
                        doctorOneId, roomTwoId, secondTemplateId, workDate),
                "med_doctor"
        );

        int finalCount = countSchedules();
        printRaceEvidence("same-doctor", race, finalCount);
        assertConflictRace(race);
        assertThat(finalCount).isEqualTo(1);
    }

    @Test
    void olderSnapshotMustRejectDifferentStartOverlapForSameClinicRoom()
            throws Exception {
        long firstTemplateId = insertTemplate(
                LocalTime.of(9, 0), LocalTime.of(11, 0));
        long secondTemplateId = insertTemplate(
                LocalTime.of(10, 0), LocalTime.of(12, 0));

        SnapshotRaceResult race = runOlderSnapshotRace(
                new CreateScheduleCommand(
                        doctorOneId, roomOneId, firstTemplateId, workDate),
                new CreateScheduleCommand(
                        doctorTwoId, roomOneId, secondTemplateId, workDate),
                "med_clinic_room"
        );

        int finalCount = countSchedules();
        printRaceEvidence("same-clinic-room", race, finalCount);
        assertConflictRace(race);
        assertThat(finalCount).isEqualTo(1);
    }

    @Test
    void adjacentSchedulesForSameDoctorAndRoomRemainValid() {
        long firstTemplateId = insertTemplate(
                LocalTime.of(9, 0), LocalTime.of(10, 0));
        long secondTemplateId = insertTemplate(
                LocalTime.of(10, 0), LocalTime.of(11, 0));

        long firstScheduleId = scheduleService.createDraft(
                new CreateScheduleCommand(
                        doctorOneId, roomOneId, firstTemplateId, workDate));
        long secondScheduleId = scheduleService.createDraft(
                new CreateScheduleCommand(
                        doctorOneId, roomOneId, secondTemplateId, workDate));

        assertThat(firstScheduleId).isPositive();
        assertThat(secondScheduleId).isPositive().isNotEqualTo(firstScheduleId);
        assertThat(countSchedules()).isEqualTo(2);
    }

    @Test
    void disjointResourcesMustBothInsertAfterConcurrentOverlapChecks()
            throws Exception {
        long firstTemplateId = insertTemplate(
                LocalTime.of(9, 0), LocalTime.of(11, 0));
        long secondTemplateId = insertTemplate(
                LocalTime.of(9, 0), LocalTime.of(11, 0));
        CreateScheduleCommand firstCommand = new CreateScheduleCommand(
                doctorOneId, roomOneId, firstTemplateId, workDate);
        CreateScheduleCommand secondCommand = new CreateScheduleCommand(
                doctorTwoId, roomTwoId, secondTemplateId, workDate);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bothReachedInsert = new CountDownLatch(2);

        doAnswer(invocation -> {
            bothReachedInsert.countDown();
            if (!bothReachedInsert.await(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(
                        "both disjoint creators must finish overlap checks "
                                + "before either insert starts");
            }
            return invocation.callRealMethod();
        }).when(scheduleRepository).insertDraft(any(ScheduleDraft.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<CreateOutcome> firstFuture = null;
        Future<CreateOutcome> secondFuture = null;
        try {
            firstFuture = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return create(firstCommand);
            });
            secondFuture = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return create(secondCommand);
            });
            assertThat(workersReady.await(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();

            CreateOutcome first = firstFuture.get(
                    FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            CreateOutcome second = secondFuture.get(
                    FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            int finalCount = countSchedules();
            System.out.printf(
                    "SCHEDULE_DISJOINT_EVIDENCE first=%s second=%s "
                            + "finalCount=%d%n",
                    first,
                    second,
                    finalCount
            );

            assertSuccessful(first);
            assertSuccessful(second);
            assertThat(second.scheduleId()).isNotEqualTo(first.scheduleId());
            assertThat(finalCount).isEqualTo(2);
        } finally {
            start.countDown();
            if (firstFuture != null && !firstFuture.isDone()) {
                firstFuture.cancel(true);
            }
            if (secondFuture != null && !secondFuture.isDone()) {
                secondFuture.cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private SnapshotRaceResult runOlderSnapshotRace(
            CreateScheduleCommand firstCommand,
            CreateScheduleCommand secondCommand,
            String waitedTable
    ) throws Exception {
        CountDownLatch firstOwnsResources = new CountDownLatch(1);
        CountDownLatch secondEstablishedSnapshot = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        doAnswer(invocation -> {
            ScheduleDraft draft = invocation.getArgument(0);
            if (draft.shiftTemplateId() == firstCommand.shiftTemplateId()) {
                firstOwnsResources.countDown();
                if (!releaseFirst.await(
                        FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError(
                            "first creator was not released by the test");
                }
            }
            return invocation.callRealMethod();
        }).when(scheduleRepository).insertDraft(any(ScheduleDraft.class));

        doAnswer(invocation -> {
            Optional<?> result = (Optional<?>) invocation.callRealMethod();
            long templateId = invocation.getArgument(0);
            if (templateId == secondCommand.shiftTemplateId()) {
                String isolation = jdbcTemplate.queryForObject(
                        "SELECT @@transaction_isolation", String.class);
                System.out.println("SCHEDULE_TX_ISOLATION " + isolation);
                assertThat(isolation).isEqualToIgnoringCase("READ-COMMITTED");
                // A real template read completed before the first insert.
                // In the original RR implementation this view was reused;
                // the fixed service must use its local RC transaction.
                secondEstablishedSnapshot.countDown();
            }
            return result;
        }).when(shiftTemplateRepository).findById(anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<CreateOutcome> firstFuture = null;
        Future<CreateOutcome> secondFuture = null;
        try {
            firstFuture = executor.submit(() -> create(firstCommand));
            assertThat(firstOwnsResources.await(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("first service transaction reached insert while holding resources")
                    .isTrue();

            secondFuture = executor.submit(() -> create(secondCommand));
            assertThat(secondEstablishedSnapshot.await(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("second service transaction established its older RR snapshot")
                    .isTrue();
            assertThat(awaitCondition(
                    SHORT_WAIT,
                    () -> resourceLockWaitIsVisible(waitedTable)))
                    .as("second service transaction waited for " + waitedTable)
                    .isTrue();

            releaseFirst.countDown();
            return new SnapshotRaceResult(
                    firstFuture.get(
                            FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    secondFuture.get(
                            FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS)
            );
        } finally {
            releaseFirst.countDown();
            if (firstFuture != null && !firstFuture.isDone()) {
                firstFuture.cancel(true);
            }
            if (secondFuture != null && !secondFuture.isDone()) {
                secondFuture.cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(
                    SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private CreateOutcome create(CreateScheduleCommand command) {
        try {
            return CreateOutcome.success(scheduleService.createDraft(command));
        } catch (BusinessException exception) {
            return CreateOutcome.businessFailure(exception.getErrorCode());
        } catch (Throwable throwable) {
            return CreateOutcome.unexpected(throwable);
        }
    }

    private void assertConflictRace(SnapshotRaceResult race) {
        assertSuccessful(race.first());

        assertThat(race.second().unexpected()).isNull();
        assertThat(race.second().scheduleId()).isZero();
        assertThat(race.second().errorCode())
                .isEqualTo(ErrorCode.SCHEDULE_CONFLICT);
    }

    private void assertSuccessful(CreateOutcome outcome) {
        assertThat(outcome.unexpected()).isNull();
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.scheduleId()).isPositive();
    }

    private void printRaceEvidence(
            String scenario,
            SnapshotRaceResult race,
            int finalCount
    ) {
        System.out.printf(
                "SCHEDULE_CONCURRENCY_EVIDENCE scenario=%s first=%s "
                        + "second=%s finalCount=%d%n",
                scenario,
                race.first(),
                race.second(),
                finalCount
        );
    }

    private boolean resourceLockWaitIsVisible(String tableName) {
        Integer waits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits lock_wait
                INNER JOIN performance_schema.data_locks requested_lock
                    ON requested_lock.ENGINE_LOCK_ID =
                        lock_wait.REQUESTING_ENGINE_LOCK_ID
                WHERE requested_lock.OBJECT_SCHEMA = DATABASE()
                  AND requested_lock.OBJECT_NAME = ?
                """, Integer.class, tableName);
        return waits != null && waits > 0;
    }

    private boolean awaitCondition(
            Duration timeout,
            BooleanSupplier condition
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private long insertDoctor(long userId, long departmentId, String name) {
        return insertAndGetId("""
                INSERT INTO med_doctor (
                    user_id, department_id, name, status
                )
                VALUES (?, ?, ?, 'ENABLED')
                """, userId, departmentId, name);
    }

    private long insertRoom(long departmentId, String name) {
        return insertAndGetId("""
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, name);
    }

    private long insertTemplate(LocalTime startTime, LocalTime endTime) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return insertAndGetId("""
                INSERT INTO med_shift_template (
                    code, name, start_time, end_time,
                    slot_duration_minutes, capacity_per_slot, status
                )
                VALUES (?, ?, ?, ?, 30, 10, 'ENABLED')
                """, TEMPLATE_CODE_PREFIX + suffix.substring(0, 8),
                "schedule-race-template-" + suffix,
                Time.valueOf(startTime), Time.valueOf(endTime));
    }

    private int countSchedules() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM med_schedule
                WHERE work_date = ?
                """, Integer.class, workDate);
        assertThat(count).isNotNull();
        return count;
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
        jdbcTemplate.update(
                "DELETE FROM med_shift_template WHERE code LIKE ?",
                TEMPLATE_CODE_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM sys_user WHERE username LIKE ?",
                TEST_USER_PREFIX + "%");
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

    private record SnapshotRaceResult(
            CreateOutcome first,
            CreateOutcome second
    ) {
    }

    private record CreateOutcome(
            long scheduleId,
            ErrorCode errorCode,
            Throwable unexpected
    ) {
        private static CreateOutcome success(long scheduleId) {
            return new CreateOutcome(scheduleId, null, null);
        }

        private static CreateOutcome businessFailure(ErrorCode errorCode) {
            return new CreateOutcome(0, errorCode, null);
        }

        private static CreateOutcome unexpected(Throwable throwable) {
            return new CreateOutcome(0, null, throwable);
        }
    }
}
