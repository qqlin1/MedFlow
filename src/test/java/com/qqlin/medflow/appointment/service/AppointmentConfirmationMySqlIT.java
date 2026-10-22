package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationBatch;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.domain.AppointmentDetail;
import com.qqlin.medflow.appointment.domain.AppointmentEventType;
import com.qqlin.medflow.appointment.domain.AppointmentPage;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.security.JwtService;
import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import com.qqlin.medflow.scheduling.domain.AvailableSlotPage;
import com.qqlin.medflow.scheduling.service.SlotQueryService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-MySQL verification for appointment lifecycle transitions.
 *
 * <p>This class deliberately refuses Spring Boot's default {@code medflow}
 * datasource. The test runner must start a disposable loopback MySQL instance
 * and point {@code MEDFLOW_CONFIRMATION_TEST_URL} to its dedicated schema.
 * Flyway then performs the same V1--V7 migrations that production uses.</p>
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@AutoConfigureMockMvc
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
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
    private AppointmentRepository appointmentRepository;

    @Autowired
    private SlotQueryService slotQueryService;

    @Autowired
    private AppointmentExpirationWorker expirationWorker;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void ownedDetailShouldShowCurrentStateAndOrderedHistory() {
        appointmentService.confirm(ownerUserId, appointmentId);
        appointmentService.cancel(ownerUserId, appointmentId);

        AppointmentDetail detail = appointmentService.getOwnedDetail(
                ownerUserId,
                appointmentId
        );

        assertThat(detail.appointment().id()).isEqualTo(appointmentId);
        assertThat(detail.appointment().patientId()).isEqualTo(patientId);
        assertThat(detail.appointment().status())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(detail.history())
                .extracting(AppointmentStatusChange::eventType)
                .containsExactly(
                        AppointmentEventType.CREATED,
                        AppointmentEventType.CONFIRMED,
                        AppointmentEventType.CANCELLED
                );
        assertThat(detail.history())
                .extracting(AppointmentStatusChange::fromStatus)
                .containsExactly(
                        null,
                        AppointmentStatus.PENDING_CONFIRMATION,
                        AppointmentStatus.BOOKED
                );
        assertThat(detail.history())
                .extracting(AppointmentStatusChange::toStatus)
                .containsExactly(
                        AppointmentStatus.PENDING_CONFIRMATION,
                        AppointmentStatus.BOOKED,
                        AppointmentStatus.CANCELLED
                );
        assertThat(detail.history())
                .extracting(AppointmentStatusChange::actorUserId)
                .containsExactly(ownerUserId, ownerUserId, ownerUserId);
    }

    @Test
    void foreignAndMissingDetailShouldHaveTheSameNotFoundBoundary() {
        long missingId = appointmentId + 1_000_000;

        for (long requestedId : new long[]{appointmentId, missingId}) {
            long userId = requestedId == appointmentId
                    ? otherOwnerUserId
                    : ownerUserId;

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> appointmentService.getOwnedDetail(userId, requestedId)
            );
            assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        assertThat(appointmentService.getOwnedDetail(
                ownerUserId,
                appointmentId
        ).appointment().id()).isEqualTo(appointmentId);
    }

    @Test
    void ownedPageShouldOrderNewestFirstAndExcludeOtherAccounts() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long secondPatientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date, phone, relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, ownerUserId, "second-patient-" + suffix);
        long foreignPatientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date, phone, relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, otherOwnerUserId, "foreign-patient-" + suffix);

        long secondAppointmentId = appointmentService.create(
                ownerUserId,
                new CreateAppointmentCommand(
                        secondPatientId,
                        slotId,
                        "owned-page-second-" + suffix
                )
        ).appointmentId();
        long foreignAppointmentId = appointmentService.create(
                otherOwnerUserId,
                new CreateAppointmentCommand(
                        foreignPatientId,
                        slotId,
                        "owned-page-foreign-" + suffix
                )
        ).appointmentId();

        jdbcTemplate.update("""
                UPDATE med_appointment
                SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 3 SECOND)
                WHERE id = ?
                """, appointmentId);
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 SECOND)
                WHERE id = ?
                """, secondAppointmentId);
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET created_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, foreignAppointmentId);

        AppointmentPage first = appointmentService.findOwnedPage(ownerUserId, 1, 1);
        AppointmentPage second = appointmentService.findOwnedPage(ownerUserId, 2, 1);
        AppointmentPage beyondEnd = appointmentService.findOwnedPage(ownerUserId, 3, 1);
        AppointmentPage foreign = appointmentService.findOwnedPage(
                otherOwnerUserId,
                1,
                10
        );

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items())
                .extracting(Appointment::id)
                .containsExactly(secondAppointmentId);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items())
                .extracting(Appointment::id)
                .containsExactly(appointmentId);
        assertThat(beyondEnd.total()).isEqualTo(2);
        assertThat(beyondEnd.items()).isEmpty();
        assertThat(foreign.total()).isEqualTo(1);
        assertThat(foreign.items())
                .extracting(Appointment::id)
                .containsExactly(foreignAppointmentId);
    }

    @Test
    void patientHttpCanCreateConfirmAndReadHistoryWithRealJwt()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long newPatientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date, phone, relationship
                )
                VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, ownerUserId, "http-patient-" + suffix);
        String ownerToken = patientToken(ownerUserId, "patient.li");

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "http-create-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":%d,"slotId":%d}
                                """.formatted(newPatientId, slotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status")
                        .value("PENDING_CONFIRMATION"));

        long createdId = requiredLong(
                "SELECT id FROM med_appointment WHERE patient_id = ?",
                newPatientId
        );

        mockMvc.perform(post("/api/v1/appointments/{id}/confirm", createdId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BOOKED"));

        mockMvc.perform(get("/api/v1/appointments/{id}", createdId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointment.status")
                        .value("BOOKED"))
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].eventType")
                        .value("CREATED"))
                .andExpect(jsonPath("$.data.history[1].eventType")
                        .value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/appointments")
                        .param("page", "1")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2));

        mockMvc.perform(get("/api/v1/appointments/{id}", createdId)
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
    void cancelAndRetryShouldRestoreCapacityOnlyOnce() {
        Appointment first = appointmentService.cancel(
                ownerUserId,
                appointmentId
        );
        Appointment retry = appointmentService.cancel(
                ownerUserId,
                appointmentId
        );

        assertThat(first.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(retry.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("CANCELLED")).isEqualTo(1);
        assertThat(statusLogFromStatus("CANCELLED"))
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION.name());
    }

    @Test
    void bookedAppointmentCanBeCancelledBeforeDeadline() {
        Appointment confirmed = appointmentService.confirm(
                ownerUserId,
                appointmentId
        );
        Appointment cancelled = appointmentService.cancel(
                ownerUserId,
                appointmentId
        );

        assertThat(confirmed.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("CONFIRMED")).isEqualTo(1);
        assertThat(statusLogCount("CANCELLED")).isEqualTo(1);
        assertThat(statusLogFromStatus("CANCELLED"))
                .isEqualTo(AppointmentStatus.BOOKED.name());
    }

    @Test
    void cancellationAfterDeadlineShouldConflictWithoutReturningCapacity() {
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET cancel_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, appointmentId);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.cancel(ownerUserId, appointmentId)
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.APPOINTMENT_CANCELLATION_EXPIRED);
        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(statusLogCount("CANCELLED")).isZero();
    }

    @Test
    void anotherPatientOwnerCannotCancelOrReturnCapacity() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/cancel",
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
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(statusLogCount("CANCELLED")).isZero();
    }

    @Test
    void expirationAndRetryShouldRestoreCapacityOnlyOnce() {
        makeConfirmationDeadlineExpired();

        boolean first = appointmentService.expirePending(appointmentId);
        boolean retry = appointmentService.expirePending(appointmentId);

        assertThat(first).isTrue();
        assertThat(retry).isFalse();
        assertThat(appointmentStatus()).isEqualTo(AppointmentStatus.EXPIRED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("EXPIRED")).isEqualTo(1);
        assertThat(systemStatusLogCount("EXPIRED")).isEqualTo(1);
    }

    @Test
    void eightConcurrentCancellationsShouldRestoreCapacityOnlyOnce()
            throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Appointment>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < threadCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(
                            SHORT_WAIT.toMillis(),
                            TimeUnit.MILLISECONDS
                    )) {
                        throw new AssertionError(
                                "concurrent cancellation start timed out"
                        );
                    }
                    return appointmentService.cancel(
                            ownerUserId,
                            appointmentId
                    );
                }));
            }

            assertThat(ready.await(
                    SHORT_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            )).isTrue();
            start.countDown();

            for (Future<Appointment> future : futures) {
                assertThat(future.get(
                        FUTURE_WAIT.toMillis(),
                        TimeUnit.MILLISECONDS
                ).status()).isEqualTo(AppointmentStatus.CANCELLED);
            }
        } finally {
            shutdownExecutor(executor);
        }

        assertThat(appointmentStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("CANCELLED")).isEqualTo(1);
    }

    @Test
    void cancellationAndExpirationRaceShouldHaveOneTerminalWinner()
            throws Exception {
        makeConfirmationDeadlineExpired();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Throwable> cancellationFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(
                        SHORT_WAIT.toMillis(),
                        TimeUnit.MILLISECONDS
                )) {
                    throw new AssertionError(
                            "cancellation race start timed out"
                    );
                }
                try {
                    appointmentService.cancel(ownerUserId, appointmentId);
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });
            Future<Boolean> expirationFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(
                        SHORT_WAIT.toMillis(),
                        TimeUnit.MILLISECONDS
                )) {
                    throw new AssertionError(
                            "expiration race start timed out"
                    );
                }
                return appointmentService.expirePending(appointmentId);
            });

            assertThat(ready.await(
                    SHORT_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            )).isTrue();
            start.countDown();

            Throwable cancellationFailure = cancellationFuture.get(
                    FUTURE_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            boolean expirationWon = expirationFuture.get(
                    FUTURE_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (expirationWon) {
                assertThat(cancellationFailure)
                        .isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) cancellationFailure)
                        .getErrorCode())
                        .isEqualTo(ErrorCode.APPOINTMENT_NOT_CANCELLABLE);
                assertThat(appointmentStatus())
                        .isEqualTo(AppointmentStatus.EXPIRED);
            } else {
                assertThat(cancellationFailure).isNull();
                assertThat(appointmentStatus())
                        .isEqualTo(AppointmentStatus.CANCELLED);
            }
        } finally {
            shutdownExecutor(executor);
        }

        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("CANCELLED")
                + statusLogCount("EXPIRED")).isEqualTo(1);
    }

    @Test
    void cancelledHistoryFailureMustRollBackStateAndCapacity() {
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_confirmation_history
                BEFORE INSERT ON med_appointment_status_log
                FOR EACH ROW
                SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'test-only cancelled history failure'
                """);

        try {
            assertThatThrownBy(
                    () -> appointmentService.cancel(
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
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(statusLogCount("CANCELLED")).isZero();
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

    @Test
    void slotBrowseMustFilterUnavailableRowsAndKeepDisplayFields() {
        LocalDate date = jdbcTemplate.queryForObject(
                "SELECT work_date FROM med_slot WHERE id = ?", LocalDate.class, slotId);
        long doctorId = requiredLong("""
                SELECT s.doctor_id FROM med_schedule s JOIN med_slot t ON t.schedule_id=s.id
                WHERE t.id = ?
                """, slotId);
        long departmentId = requiredLong("SELECT department_id FROM med_doctor WHERE id = ?", doctorId);
        AvailableSlotPage page = slotQueryService.findAvailable(date, departmentId, doctorId, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(AvailableSlot::slotId).containsExactly(slotId);
        assertThat(page.items().getFirst().remainingCapacity()).isEqualTo(9);
        assertThat(page.items().getFirst().doctorName()).isNotBlank();
        assertThat(slotQueryService.findAvailable(date, Long.MAX_VALUE, null, 1, 20).total()).isZero();
        assertThat(slotQueryService.findAvailable(date, null, Long.MAX_VALUE, 1, 20).total()).isZero();

        jdbcTemplate.update("UPDATE med_slot SET status='CLOSED' WHERE id=?", slotId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 20).total()).isZero();
        jdbcTemplate.update("UPDATE med_slot SET status='OPEN', remaining_capacity=0 WHERE id=?", slotId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 20).total()).isZero();
        jdbcTemplate.update("UPDATE med_slot SET remaining_capacity=9 WHERE id=?", slotId);
        LocalDateTime initialDeadline = jdbcTemplate.queryForObject(
                "SELECT booking_deadline FROM med_slot WHERE id=?", LocalDateTime.class, slotId);
        jdbcTemplate.update("""
                UPDATE med_slot SET booking_deadline=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id=?
                """, slotId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 20).total()).isZero();
        jdbcTemplate.update("UPDATE med_slot SET booking_deadline=? WHERE id=?", initialDeadline, slotId);
        long scheduleId = requiredLong("SELECT schedule_id FROM med_slot WHERE id=?", slotId);
        jdbcTemplate.update("UPDATE med_schedule SET status='DRAFT' WHERE id=?", scheduleId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 20).total()).isZero();
        jdbcTemplate.update("UPDATE med_schedule SET status='PUBLISHED' WHERE id=?", scheduleId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 20).total()).isEqualTo(1);
        jdbcTemplate.update("UPDATE med_slot SET work_date=DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY) WHERE id=?", slotId);
        LocalDate yesterday = jdbcTemplate.queryForObject("SELECT DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY)", LocalDate.class);
        assertThat(slotQueryService.findAvailable(yesterday, null, null, 1, 20).total()).isZero();
    }

    @Test
    void slotBrowsePaginationMustKeepSameStartTimeRowsInIdOrder() {
        LocalDate date = jdbcTemplate.queryForObject(
                "SELECT work_date FROM med_slot WHERE id = ?", LocalDate.class, slotId);
        // Deliberately seed equal start times to verify SQL's secondary ordering.
        long sameTime = insertAndGetId("""
                INSERT INTO med_slot (schedule_id, work_date, start_time, end_time,
                    booking_deadline, total_capacity, remaining_capacity, status)
                SELECT schedule_id, work_date, '09:00:00', '09:15:00',
                    booking_deadline, total_capacity, remaining_capacity, status
                FROM med_slot WHERE id=?
                """, slotId);
        long laterTime = insertAndGetId("""
                INSERT INTO med_slot (schedule_id, work_date, start_time, end_time,
                    booking_deadline, total_capacity, remaining_capacity, status)
                SELECT schedule_id, work_date, '09:15:00', '09:30:00',
                    booking_deadline, total_capacity, remaining_capacity, status
                FROM med_slot WHERE id=?
                """, slotId);
        assertThat(slotQueryService.findAvailable(date, null, null, 1, 1).items())
                .extracting(AvailableSlot::slotId).containsExactly(slotId);
        assertThat(slotQueryService.findAvailable(date, null, null, 2, 1).items())
                .extracting(AvailableSlot::slotId).containsExactly(sameTime);
        assertThat(slotQueryService.findAvailable(date, null, null, 3, 1).items())
                .extracting(AvailableSlot::slotId).containsExactly(laterTime);
        AvailableSlotPage tail = slotQueryService.findAvailable(date, null, null, 4, 1);
        assertThat(tail.total()).isEqualTo(3);
        assertThat(tail.items()).isEmpty();
    }

    @Test
    void creationWaitingPastBookingDeadlineMustRollbackEveryNewWrite() throws Exception {
        CreationLockOutcome outcome = createWhileSlotLocked(true);
        assertThat(outcome.failure()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) outcome.failure()).getErrorCode())
                .isEqualTo(ErrorCode.SLOT_BOOKING_EXPIRED);
        assertThat(outcome.creation()).isNull();
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment"))
                .isEqualTo(1);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment_status_log"))
                .isEqualTo(2);
        assertThat(requiredLong("""
                SELECT COUNT(*) FROM sys_idempotency_record
                WHERE idempotency_key = ?
                """, outcome.key())).isZero();
    }

    @Test
    void creationConfirmationWindowMustUseDatabaseClock() {
        appointmentService.cancel(ownerUserId, appointmentId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            long shiftedSeconds = requiredLong("SELECT UNIX_TIMESTAMP() + 3600");
            try {
                jdbcTemplate.execute("SET SESSION timestamp = " + shiftedSeconds);
                LocalDateTime databaseNow = jdbcTemplate.queryForObject(
                        "SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
                assertThat(databaseNow).isAfter(LocalDateTime.now().plusMinutes(50));
                AppointmentCreationResult created = appointmentService.create(ownerUserId,
                        new CreateAppointmentCommand(patientId, slotId,
                                "create-db-clock-" + UUID.randomUUID()));
                assertThat(created.confirmDeadline()).isEqualTo(databaseNow.plusMinutes(15));
            } finally {
                // Reset the same transactional connection before it returns to Hikari.
                jdbcTemplate.execute("SET SESSION timestamp = 0");
            }
        });
    }

    @Test
    void creationReleasedBeforeBookingDeadlineMustSucceed() throws Exception {
        CreationLockOutcome outcome = createWhileSlotLocked(false);
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.creation()).isNotNull();
        assertThat(outcome.creation().confirmDeadline()).isEqualTo(outcome.deadline());
        assertThat(outcome.creation().cancelDeadline()).isEqualTo(outcome.deadline());
        assertThat(remainingCapacity()).isEqualTo(9);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment"))
                .isEqualTo(2);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment_status_log"))
                .isEqualTo(3);
        assertThat(requiredLong("""
                SELECT COUNT(*) FROM sys_idempotency_record
                WHERE idempotency_key = ? AND status = 'SUCCESS'
                """, outcome.key())).isEqualTo(1);
    }

    private CreationLockOutcome createWhileSlotLocked(boolean crossDeadline) throws Exception {
        appointmentService.cancel(ownerUserId, appointmentId);
        jdbcTemplate.update("""
                UPDATE med_slot
                SET booking_deadline = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 3 SECOND)
                WHERE id = ?
                """, slotId);
        if (!crossDeadline) {
            jdbcTemplate.update("""
                    UPDATE med_slot
                    SET booking_deadline = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)
                    WHERE id = ?
                    """, slotId);
        }
        LocalDateTime deadline = jdbcTemplate.queryForObject(
                "SELECT booking_deadline FROM med_slot WHERE id = ?",
                LocalDateTime.class, slotId);
        assertThat(deadline).isNotNull();
        assertThat(LocalDateTime.now()).isBefore(deadline);
        String key = "create-lock-deadline-" + UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = lockConnection.prepareStatement(
                        "SELECT id FROM med_slot WHERE id = ? FOR UPDATE")) {
                    statement.setLong(1, slotId);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                    }
                }
                Future<CreationLockOutcome> future = executor.submit(() -> {
                    try {
                        return new CreationLockOutcome(key, deadline,
                                appointmentService.create(ownerUserId,
                                        new CreateAppointmentCommand(patientId, slotId, key)), null);
                    } catch (Throwable failure) {
                        return new CreationLockOutcome(key, deadline, null, failure);
                    }
                });
                awaitCondition("creation must wait on the held med_slot row", SHORT_WAIT,
                        () -> requiredLong("""
                                SELECT COUNT(*)
                                FROM performance_schema.data_lock_waits w
                                JOIN performance_schema.data_locks l
                                  ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                                WHERE l.OBJECT_SCHEMA = DATABASE()
                                  AND l.OBJECT_NAME = 'med_slot'
                                """) > 0);
                assertThat(databaseTimeIsAfter(lockConnection, deadline)).isFalse();
                if (crossDeadline) {
                    awaitDatabaseTimeAfter(lockConnection, deadline, DEADLINE_WAIT);
                }
                assertThat(future.isDone()).isFalse();
                LocalDateTime releasedAt = jdbcTemplate.queryForObject(
                        "SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
                lockConnection.commit();
                CreationLockOutcome outcome = future.get(FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                System.out.printf("CREATE_LOCK_DEADLINE cross=%s deadline=%s release=%s result=%s "
                                + "capacity=%d appointments=%d history=%d keyRows=%d%n",
                        crossDeadline, deadline, releasedAt,
                        outcome.failure() == null ? "SUCCESS" : outcome.failure().getClass().getSimpleName(),
                        remainingCapacity(), requiredLong("SELECT COUNT(*) FROM med_appointment"),
                        requiredLong("SELECT COUNT(*) FROM med_appointment_status_log"),
                        requiredLong("SELECT COUNT(*) FROM sys_idempotency_record WHERE idempotency_key = ?", key));
                return outcome;
            } finally {
                lockConnection.rollback();
            }
        } finally {
            shutdownExecutor(executor);
        }
    }

    private record CreationLockOutcome(String key, LocalDateTime deadline,
                                       AppointmentCreationResult creation, Throwable failure) {
    }

    @Test
    void cursorKeepsSameMillisecondRowsAndIgnoresNewRowsBeforeBoundary() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 2, 10, 0, 0, 123_000_000);
        jdbcTemplate.update("UPDATE med_appointment SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(time.minusMinutes(2)), appointmentId);
        long first = insertCursorAppointment(patientId, time);
        long second = insertCursorAppointment(patientId, time);
        long third = insertCursorAppointment(patientId, time);
        long earlierButLargerId = insertCursorAppointment(patientId, time.minusMinutes(1));

        AppointmentSlice page1 = appointmentService.findOwnedSlice(ownerUserId, 2, null);
        assertThat(page1.items()).extracting(AppointmentCursorItem::id)
                .containsExactly(third, second);
        assertThat(page1.nextCursor()).isEqualTo(new AppointmentCursor(time, second));
        insertCursorAppointment(patientId, time.plusMinutes(1));

        AppointmentSlice page2 = appointmentService.findOwnedSlice(
                ownerUserId, 2, page1.nextCursor());
        assertThat(page2.items()).extracting(AppointmentCursorItem::id)
                .containsExactly(first, earlierButLargerId);
        AppointmentSlice page3 = appointmentService.findOwnedSlice(
                ownerUserId, 2, page2.nextCursor());
        assertThat(page3.items()).extracting(AppointmentCursorItem::id)
                .containsExactly(appointmentId);
        assertThat(page3.hasNext()).isFalse();
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void multiPatientCursorPreservesGlobalOrderAndOwnerFiltering() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 2, 10, 0);
        jdbcTemplate.update("UPDATE med_appointment SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(time.minusMinutes(1)), appointmentId);
        long secondPatient = insertAndGetId("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES (?,'cursor-family','UNKNOWN','1990-01-01','13900000001','FAMILY')
                """, ownerUserId);
        long foreignPatient = insertAndGetId("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES (?,'cursor-foreign','UNKNOWN','1990-01-01','13900000002','SELF')
                """, otherOwnerUserId);
        long familyAppointment = insertCursorAppointment(secondPatient, time);
        long latestOwn = insertCursorAppointment(patientId, time.plusMinutes(1));
        insertCursorAppointment(foreignPatient, time.plusMinutes(2));

        AppointmentSlice first = appointmentService.findOwnedSlice(ownerUserId, 1, null);
        assertThat(first.items()).extracting(AppointmentCursorItem::id)
                .containsExactly(latestOwn);
        AppointmentSlice second = appointmentService.findOwnedSlice(
                ownerUserId, 1, first.nextCursor());
        assertThat(second.items()).extracting(AppointmentCursorItem::id)
                .containsExactly(familyAppointment);
        assertThat(appointmentRepository.findSliceOwnedBySinglePatient(
                secondPatient, otherOwnerUserId, null, 51)).isEmpty();
    }

    @Test
    void realMySqlAccountStateAndVersionChangesInvalidateExistingBearer() throws Exception {
        String bearer = "Bearer " + patientToken(ownerUserId, "patient.li");
        int originalVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM sys_user WHERE id = ?", Integer.class, ownerUserId);
        try {
            mockMvc.perform(get("/api/v1/appointments/cursor")
                            .header("Authorization", bearer)).andExpect(status().isOk());
            jdbcTemplate.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", ownerUserId);
            mockMvc.perform(get("/api/v1/appointments/cursor")
                            .header("Authorization", bearer)).andExpect(status().isUnauthorized());
            jdbcTemplate.update("UPDATE sys_user SET status='ENABLED', token_version=? WHERE id=?",
                    originalVersion + 1, ownerUserId);
            mockMvc.perform(get("/api/v1/appointments/cursor")
                            .header("Authorization", bearer)).andExpect(status().isUnauthorized());
            UserAccount updated = new UserAccount(ownerUserId, "patient.li", "unused",
                    UserRole.PATIENT, UserStatus.ENABLED, originalVersion + 1);
            mockMvc.perform(get("/api/v1/appointments/cursor")
                            .header("Authorization", "Bearer " + jwtService.issue(updated)))
                    .andExpect(status().isOk());
        } finally {
            jdbcTemplate.update("UPDATE sys_user SET status='ENABLED', token_version=? WHERE id=?",
                    originalVersion, ownerUserId);
        }
    }

    @Test
    void dueScanIsBoundedAndOrdersEqualDeadlinesByIdUsingExistingIndex() {
        long second = createAdditionalPendingAppointment();
        long third = createAdditionalPendingAppointment();
        long future = createAdditionalPendingAppointment();
        long booked = createAdditionalPendingAppointment();
        appointmentService.confirm(ownerUserId, booked);
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                WHERE id <> ?
                """, future);

        List<AppointmentExpirationCandidate> first = appointmentRepository.findDuePendingAfter(null, 2);
        assertThat(first).extracting(AppointmentExpirationCandidate::id)
                .containsExactly(appointmentId, second);
        assertThat(first.getFirst().confirmDeadline()).isEqualTo(first.getLast().confirmDeadline());
        assertThat(appointmentRepository.findDuePendingAfter(first.getLast(), 2))
                .extracting(AppointmentExpirationCandidate::id).containsExactly(third);

        String plan = jdbcTemplate.queryForObject("""
                EXPLAIN ANALYZE SELECT id, confirm_deadline
                FROM med_appointment
                WHERE status = 'PENDING_CONFIRMATION'
                  AND confirm_deadline <= CURRENT_TIMESTAMP(3)
                ORDER BY confirm_deadline ASC, id ASC LIMIT 2
                """, String.class);
        assertThat(plan).contains("idx_appointment_pending_timeout", "Covering index").doesNotContain("Sort:");
        System.out.println("EXPIRATION_SCAN_PLAN " + plan);
    }

    @Test
    void failedHeadBatchRollsBackEachItemWithoutStarvingLaterAppointments() {
        long second = createAdditionalPendingAppointment();
        long third = createAdditionalPendingAppointment();
        long fourth = createAdditionalPendingAppointment();
        jdbcTemplate.update("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                """);
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(
                appointmentRepository, appointmentService, 2);
        // Only test-generated numeric IDs enter this trigger definition.
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_confirmation_history
                BEFORE INSERT ON med_appointment_status_log
                FOR EACH ROW BEGIN
                    IF NEW.event_type = 'EXPIRED' AND NEW.appointment_id IN (%d, %d) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test-only expiration failure';
                    END IF;
                END
                """.formatted(appointmentId, second));
        try {
            assertThat(worker.runBatch()).isEqualTo(new AppointmentExpirationBatch(2, 0, 0, 2));
            assertThat(remainingCapacity()).isEqualTo(6);
            assertThat(appointmentStatus()).isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
            assertThat(statusLogCount("EXPIRED")).isZero();
            assertThat(worker.runBatch()).isEqualTo(new AppointmentExpirationBatch(2, 2, 0, 0));
            assertThat(remainingCapacity()).isEqualTo(8);
            assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment WHERE id IN (?, ?) AND status = 'EXPIRED'",
                    third, fourth)).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME);
        }
        assertThat(worker.runBatch()).isEqualTo(new AppointmentExpirationBatch(2, 2, 0, 0));
        assertThat(worker.runBatch()).isEqualTo(new AppointmentExpirationBatch(0, 0, 0, 0));
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment WHERE status = 'EXPIRED'")).isEqualTo(4);
        assertThat(requiredLong("SELECT COUNT(*) FROM med_appointment_status_log WHERE event_type = 'EXPIRED'"))
                .isEqualTo(4);
    }

    @Test
    void expirationWorkerSuspendsOuterTransactionAndCommitsEachAppointment() {
        makeConfirmationDeadlineExpired();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.executeWithoutResult(transaction -> {
            assertThat(expirationWorker.runBatch()).isEqualTo(new AppointmentExpirationBatch(1, 1, 0, 0));
            transaction.setRollbackOnly();
        });
        assertThat(appointmentStatus()).isEqualTo(AppointmentStatus.EXPIRED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("EXPIRED")).isEqualTo(1);
    }

    @Test
    void twoExpirationWorkersReturnCapacityOnceWhenBothScanSameAppointment() throws Exception {
        makeConfirmationDeadlineExpired();
        CountDownLatch scanned = new CountDownLatch(2);
        // Coordinate only the scan; the injected AppointmentService still uses
        // real Spring transaction proxies and MySQL locks for both workers.
        AppointmentRepository coordinatedRepository = new AppointmentRepository(null) {
            @Override
            public List<AppointmentExpirationCandidate> findDuePendingAfter(
                    AppointmentExpirationCandidate cursor, int limit) {
                List<AppointmentExpirationCandidate> result = appointmentRepository.findDuePendingAfter(cursor, limit);
                scanned.countDown();
                try {
                    if (!scanned.await(SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("both workers must scan before expiring");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AppointmentExpirationBatch> first = executor.submit(() ->
                    new AppointmentExpirationWorker(coordinatedRepository, appointmentService, 2).runBatch());
            Future<AppointmentExpirationBatch> second = executor.submit(() ->
                    new AppointmentExpirationWorker(coordinatedRepository, appointmentService, 2).runBatch());
            AppointmentExpirationBatch a = first.get(FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            AppointmentExpirationBatch b = second.get(FUTURE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(a.scanned() + b.scanned()).isEqualTo(2);
            assertThat(a.expired() + b.expired()).isEqualTo(1);
            assertThat(a.skipped() + b.skipped()).isEqualTo(1);
            assertThat(a.failed() + b.failed()).isZero();
        } finally {
            shutdownExecutor(executor);
        }
        assertThat(appointmentStatus()).isEqualTo(AppointmentStatus.EXPIRED);
        assertThat(remainingCapacity()).isEqualTo(10);
        assertThat(statusLogCount("EXPIRED")).isEqualTo(1);
    }

    private long createAdditionalPendingAppointment() {
        long additionalPatient = insertAndGetId("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES (?,'expiration-family','UNKNOWN','2000-01-01','13800138001','FAMILY')
                """, ownerUserId);
        return appointmentService.create(ownerUserId, new CreateAppointmentCommand(
                additionalPatient, slotId, "expiration-test-" + UUID.randomUUID())).appointmentId();
    }

    private long insertCursorAppointment(long targetPatientId, LocalDateTime createdAt) {
        return insertAndGetId("""
                INSERT INTO med_appointment
                    (patient_id,slot_id,status,confirm_deadline,cancel_deadline,created_at)
                VALUES (?,?,'CANCELLED',?,?,?)
                """, targetPatientId, slotId,
                Timestamp.valueOf(createdAt.plusMinutes(15)),
                Timestamp.valueOf(createdAt.plusHours(1)), Timestamp.valueOf(createdAt));
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

    private String statusLogFromStatus(String eventType) {
        String fromStatus = jdbcTemplate.queryForObject("""
                SELECT from_status
                FROM med_appointment_status_log
                WHERE appointment_id = ?
                  AND event_type = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, appointmentId, eventType);
        assertThat(fromStatus).isNotBlank();
        return fromStatus;
    }

    private int systemStatusLogCount(String eventType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM med_appointment_status_log
                WHERE appointment_id = ?
                  AND event_type = ?
                  AND actor_user_id IS NULL
                """, Integer.class, appointmentId, eventType);
        assertThat(count).isNotNull();
        return count;
    }

    private void makeConfirmationDeadlineExpired() {
        int affectedRows = jdbcTemplate.update("""
                UPDATE med_appointment
                SET confirm_deadline =
                    DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, appointmentId);
        assertThat(affectedRows).isEqualTo(1);
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
