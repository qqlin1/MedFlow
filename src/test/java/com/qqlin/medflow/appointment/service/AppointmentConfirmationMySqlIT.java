package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.security.JwtService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.GeneratedKeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL verification for appointment confirmation.
 *
 * <p>This class deliberately refuses Spring Boot's default {@code medflow}
 * datasource. The test runner must start a disposable loopback MySQL instance
 * and point {@code MEDFLOW_CONFIRMATION_TEST_URL} to its dedicated schema.
 * Flyway then performs the same V1--V7 migrations that production uses.</p>
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@AutoConfigureMockMvc
@SpringBootTest
class AppointmentConfirmationMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final String OTHER_PATIENT_USERNAME =
            "patient.confirmation.other";
    private static final String TRIGGER_NAME =
            "test_fail_confirmation_history";
    private static final Duration SHORT_WAIT = Duration.ofSeconds(5);
    private static final Duration FUTURE_WAIT = Duration.ofSeconds(10);
    private static final Duration DEADLINE_WAIT = Duration.ofSeconds(6);

    /* Base64 for exactly 32 test-only bytes. It is never a production secret. */
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA
                    + "(?:\\?[^\\s]*)?$"
    );

    private static final String TEST_DATABASE_URL =
            requireIsolatedDatabaseUrl();

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    private long ownerUserId;
    private long otherOwnerUserId;
    private long patientId;
    private long slotId;
    private long appointmentId;

    @DynamicPropertySource
    static void isolatedMySqlProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add(
                "spring.datasource.hikari.maximum-pool-size",
                () -> "12"
        );
        registry.add(
                "spring.datasource.hikari.connection-timeout",
                () -> "3000"
        );
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 8"
        );
        registry.add(
                "medflow.security.jwt.secret",
                () -> TEST_ONLY_JWT_SECRET
        );
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @BeforeEach
    void setUpFixture() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        )).isEqualTo(TEST_SCHEMA);

        cleanMutableTestData();

        ownerUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?",
                "patient.li"
        );
        long doctorUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?",
                "doctor.wang"
        );

        jdbcTemplate.update("""
                INSERT INTO sys_user (
                    username,
                    password_hash,
                    role,
                    status,
                    token_version
                )
                VALUES (?, 'test-only-not-used-for-login', 'PATIENT', 'ENABLED', 0)
                """, OTHER_PATIENT_USERNAME);
        otherOwnerUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = ?",
                OTHER_PATIENT_USERNAME
        );

        long templateId = requiredLong(
                "SELECT id FROM med_shift_template WHERE code = 'MORNING'"
        );
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long departmentId = insertAndGetId("""
                INSERT INTO med_department (name, status)
                VALUES (?, 'ENABLED')
                """, "confirm-department-" + suffix);
        long doctorId = insertAndGetId("""
                INSERT INTO med_doctor (user_id, department_id, name, status)
                VALUES (?, ?, ?, 'ENABLED')
                """, doctorUserId, departmentId, "confirm-doctor-" + suffix);
        long clinicRoomId = insertAndGetId("""
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, "confirm-room-" + suffix);
        long scheduleId = insertAndGetId("""
                INSERT INTO med_schedule (
                    doctor_id,
                    clinic_room_id,
                    shift_template_id,
                    work_date,
                    start_time,
                    end_time,
                    slot_duration_minutes,
                    capacity_per_slot,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00',
                    '09:30:00',
                    30,
                    10,
                    'PUBLISHED'
                )
                """, doctorId, clinicRoomId, templateId);
        slotId = insertAndGetId("""
                INSERT INTO med_slot (
                    schedule_id,
                    work_date,
                    start_time,
                    end_time,
                    booking_deadline,
                    total_capacity,
                    remaining_capacity,
                    status
                )
                VALUES (
                    ?,
                    DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00',
                    '09:30:00',
                    DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),
                    10,
                    10,
                    'OPEN'
                )
                """, scheduleId);
        patientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id,
                    name,
                    gender,
                    birth_date,
                    phone,
                    relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, ownerUserId, "confirm-patient-" + suffix);

        AppointmentCreationResult creation = appointmentService.create(
                ownerUserId,
                new CreateAppointmentCommand(
                        patientId,
                        slotId,
                        "confirmation-it-" + suffix
                )
        );
        appointmentId = creation.appointmentId();

        assertThat(creation.status())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(statusLogCount("CREATED")).isEqualTo(1);
        assertThat(statusLogCount("CONFIRMED")).isZero();
    }

    @AfterEach
    void removeTestOnlyDatabaseObjects() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME);
        cleanMutableTestData();
    }

    @Test
    void confirmAndRetryShouldKeepCapacityAtNineAndWriteOneHistoryRow() {
        Appointment first = appointmentService.confirm(
                ownerUserId,
                appointmentId
        );
        Appointment retry = appointmentService.confirm(
                ownerUserId,
                appointmentId
        );

        assertThat(first.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(retry.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(statusLogCount("CONFIRMED")).isEqualTo(1);
    }

    @Test
    void anotherPatientOwnerShouldReceive404AndLeaveAppointmentUntouched()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        appointmentId
                )
                        .header(
                                "Authorization",
                                "Bearer " + patientToken(
                                        otherOwnerUserId,
                                        OTHER_PATIENT_USERNAME
                                )
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("APPOINTMENT_NOT_FOUND"));

        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(statusLogCount("CONFIRMED")).isZero();
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    @Test
    void confirmationAfterDeadlineShouldReturnConflictAndNotWriteHistory() {
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, appointmentId);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(ownerUserId, appointmentId)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.APPOINTMENT_CONFIRMATION_EXPIRED);
        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(statusLogCount("CONFIRMED")).isZero();
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    @ParameterizedTest
    @EnumSource(
            value = AppointmentStatus.class,
            names = {"CANCELLED", "EXPIRED"}
    )
    void cancelledOrExpiredAppointmentMustNotBeRevived(
            AppointmentStatus terminalStatus
    ) {
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET status = ?
                WHERE id = ?
                """, terminalStatus.name(), appointmentId);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(ownerUserId, appointmentId)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.APPOINTMENT_NOT_CONFIRMABLE);
        assertThat(appointmentStatus()).isEqualTo(terminalStatus);
        assertThat(statusLogCount("CONFIRMED")).isZero();
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    @Test
    void confirmedHistoryFailureMustRollBackBookedState() {
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_confirmation_history
                BEFORE INSERT ON med_appointment_status_log
                FOR EACH ROW
                SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'test-only confirmed history failure'
                """);

        try {
            assertThatThrownBy(
                    () -> appointmentService.confirm(
                            ownerUserId,
                            appointmentId
                    )
            ).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS " + TRIGGER_NAME
            );
        }

        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(statusLogCount("CONFIRMED")).isZero();
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    @Test
    void eightConcurrentDoubleConfirmationsShouldWriteOnlyOneHistoryRow()
            throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Appointment>>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < threadCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(
                            SHORT_WAIT.toMillis(),
                            TimeUnit.MILLISECONDS
                    )) {
                        throw new AssertionError(
                                "concurrent confirmation start timed out"
                        );
                    }

                    Appointment first = appointmentService.confirm(
                            ownerUserId,
                            appointmentId
                    );
                    Appointment retry = appointmentService.confirm(
                            ownerUserId,
                            appointmentId
                    );

                    return List.of(first, retry);
                }));
            }

            assertThat(ready.await(
                    SHORT_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            )).isTrue();
            start.countDown();

            for (Future<List<Appointment>> future : futures) {
                List<Appointment> confirmations = future.get(
                        FUTURE_WAIT.toMillis(),
                        TimeUnit.MILLISECONDS
                );
                assertThat(confirmations)
                        .hasSize(2)
                        .allSatisfy(confirmation -> assertThat(
                                confirmation.status()
                        ).isEqualTo(AppointmentStatus.BOOKED));
            }
        } finally {
            shutdownExecutor(executor);
        }

        assertThat(appointmentStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(statusLogCount("CONFIRMED")).isEqualTo(1);
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    @Test
    void lockWaitPastDeadlineMustRejectUsingDatabaseTime() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            boolean committed = false;

            try {
                LocalDateTime deadline = setDeadlineAndLock(
                        lockConnection,
                        appointmentId
                );

                Future<Throwable> confirmationFuture = executor.submit(() -> {
                    try {
                        appointmentService.confirm(ownerUserId, appointmentId);
                        return null;
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                });

                awaitCondition(
                        "confirmation request should wait for med_appointment lock",
                        SHORT_WAIT,
                        this::isAppointmentLockWaitVisible
                );
                awaitDatabaseTimeAfter(
                        lockConnection,
                        deadline,
                        DEADLINE_WAIT
                );

                lockConnection.commit();
                committed = true;

                Throwable confirmationFailure = confirmationFuture.get(
                        FUTURE_WAIT.toMillis(),
                        TimeUnit.MILLISECONDS
                );
                assertThat(confirmationFailure)
                        .isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) confirmationFailure)
                        .getErrorCode())
                        .isEqualTo(
                                ErrorCode.APPOINTMENT_CONFIRMATION_EXPIRED
                        );
            } finally {
                if (!committed) {
                    lockConnection.rollback();
                }
            }
        } finally {
            shutdownExecutor(executor);
        }

        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(statusLogCount("CONFIRMED")).isZero();
        assertThat(remainingCapacity()).isEqualTo(9);
    }

    private static String requireIsolatedDatabaseUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher matcher = url == null
                ? null
                : ISOLATED_URL_PATTERN.matcher(url);

        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must point only to "
                            + "jdbc:mysql://127.0.0.1:<random-port>/"
                            + TEST_SCHEMA
            );
        }

        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must use a non-default "
                            + "temporary MySQL port"
            );
        }

        return url;
    }

    private void cleanMutableTestData() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME);
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
                "DELETE FROM sys_user WHERE username = ?",
                OTHER_PATIENT_USERNAME
        );
    }

    private long insertAndGetId(String sql, Object... arguments) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int affectedRows = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, keyHolder);

        assertThat(affectedRows).isEqualTo(1);
        Number generatedId = keyHolder.getKey();
        assertThat(generatedId).isNotNull();
        return generatedId.longValue();
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments
        );
        assertThat(value).isNotNull();
        return value;
    }

    private int remainingCapacity() {
        Integer capacity = jdbcTemplate.queryForObject(
                "SELECT remaining_capacity FROM med_slot WHERE id = ?",
                Integer.class,
                slotId
        );
        assertThat(capacity).isNotNull();
        return capacity;
    }

    private AppointmentStatus appointmentStatus() {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM med_appointment WHERE id = ?",
                String.class,
                appointmentId
        );
        assertThat(status).isNotBlank();
        return AppointmentStatus.valueOf(status);
    }

    private int statusLogCount(String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM med_appointment_status_log
                WHERE appointment_id = ?
                  AND event_type = ?
                """, Integer.class, appointmentId, eventType);
        assertThat(count).isNotNull();
        return count;
    }

    private String patientToken(long userId, String username) {
        return jwtService.issue(new UserAccount(
                userId,
                username,
                "test-only-not-used-for-login",
                UserRole.PATIENT,
                UserStatus.ENABLED,
                0
        ));
    }

    private LocalDateTime setDeadlineAndLock(
            Connection connection,
            long targetAppointmentId
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 2 SECOND)
                WHERE id = ?
                """)) {
            update.setLong(1, targetAppointmentId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }

        try (PreparedStatement lock = connection.prepareStatement("""
                SELECT confirm_deadline
                FROM med_appointment
                WHERE id = ?
                FOR UPDATE
                """)) {
            lock.setLong(1, targetAppointmentId);
            try (ResultSet resultSet = lock.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                Timestamp deadline = resultSet.getTimestamp("confirm_deadline");
                assertThat(deadline).isNotNull();
                return deadline.toLocalDateTime();
            }
        }
    }

    private boolean isAppointmentLockWaitVisible() {
        Integer waits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits lock_wait
                INNER JOIN performance_schema.data_locks requested_lock
                    ON requested_lock.ENGINE_LOCK_ID =
                        lock_wait.REQUESTING_ENGINE_LOCK_ID
                WHERE requested_lock.OBJECT_SCHEMA = DATABASE()
                  AND requested_lock.OBJECT_NAME = 'med_appointment'
                """, Integer.class);
        return waits != null && waits > 0;
    }

    private void awaitDatabaseTimeAfter(
            Connection connection,
            LocalDateTime deadline,
            Duration timeout
    ) throws InterruptedException {
        awaitCondition(
                "database time should pass confirmation deadline",
                timeout,
                () -> databaseTimeIsAfter(connection, deadline)
        );
    }

    private boolean databaseTimeIsAfter(
            Connection connection,
            LocalDateTime deadline
    ) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CURRENT_TIMESTAMP(3) > ?"
        )) {
            statement.setTimestamp(1, Timestamp.valueOf(deadline));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "unable to read database time from isolated test instance",
                    exception
            );
        }
    }

    private void awaitCondition(
            String description,
            Duration timeout,
            BooleanSupplier condition
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + description);
    }

    private void shutdownExecutor(ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(
                SHORT_WAIT.toMillis(),
                TimeUnit.MILLISECONDS
        )).isTrue();
    }
}
