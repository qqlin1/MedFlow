package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.scheduling.service.ScheduleService;
import com.qqlin.medflow.scheduling.service.SlotBookingService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Runs only against tools/test-confirmation.ps1's disposable MySQL schema. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class ScheduleManagementMySqlIT {
    private static final String DATABASE_URL = isolatedUrl();
    @Autowired ScheduleManagementService management;
    @Autowired ScheduleService schedules;
    @Autowired AppointmentService appointments;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ScheduleManagementRepository repository;
    @MockitoSpyBean SlotBookingService booking;
    long doctorId, doctorUserId, roomId, templateId, ownerId, patientId, adminId;
    LocalDate date;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout = 12");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("medflow_confirmation_test");
        cleanup();
        date = LocalDate.now().plusDays(5);
        ownerId = scalar("SELECT id FROM sys_user WHERE username='patient.li'");
        doctorUserId = scalar("SELECT id FROM sys_user WHERE username='doctor.wang'");
        adminId = scalar("SELECT id FROM sys_user WHERE username='admin'");
        long department = insert("INSERT INTO med_department(name,status) VALUES (?, 'ENABLED')", "management-test-" + UUID.randomUUID());
        doctorId = insert("INSERT INTO med_doctor(user_id,department_id,name,status) VALUES (?,?,'测试医生','ENABLED')", doctorUserId, department);
        roomId = insert("INSERT INTO med_clinic_room(department_id,name,status) VALUES (?,'测试诊室','ENABLED')", department);
        templateId = scalar("SELECT id FROM med_shift_template WHERE code='MORNING'");
        patientId = insert("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES (?,'虚构就诊人','UNKNOWN','2000-01-01','13800138000','SELF')
                """, ownerId);
    }

    @AfterEach void cleanup() {
        for (String table : List.of("med_encounter_status_log", "med_medical_record", "med_encounter",
                "med_appointment_status_log", "sys_idempotency_record", "med_appointment", "med_slot",
                "med_schedule", "med_patient", "med_doctor", "med_clinic_room", "med_department")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'management-test-%'");
    }

    @Test void batchConflictOnSecondDateRollsBackFirstDate() {
        draft(date.plusDays(1));
        assertThatThrownBy(() -> management.createBatch(new BatchRequest(doctorId, roomId, templateId,
                List.of(date, date.plusDays(1)))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_CONFLICT);
                    assertThat(error.getMessage()).contains(date.plusDays(1).toString());
                });
        assertThat(scalar("SELECT COUNT(*) FROM med_schedule")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM med_schedule WHERE work_date = ?", date)).isZero();
    }

    @Test void directoryAndAppointmentJoinsAreScopedToBoundDoctor() {
        long id = published();
        long slot = firstSlot(id);
        book(slot);
        Page<ScheduleItem> page = management.findDoctorPage(doctorUserId, date, date, null, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        ScheduleItem item = page.items().getFirst();
        assertThat(item.doctorName()).isEqualTo("测试医生");
        assertThat(item.totalCapacity()).isEqualTo(80);
        assertThat(item.remainingCapacity()).isEqualTo(79);
        assertThat(item.activeAppointmentCount()).isEqualTo(1);
        assertThat(management.detail(id).slots()).hasSize(8);
        Page<DoctorAppointment> mine = management.doctorAppointments(doctorUserId, date, 1, 20);
        assertThat(mine.total()).isEqualTo(1);
        assertThat(mine.items().getFirst().patientName()).isEqualTo("虚构就诊人");
        assertThat(mine.items().getFirst().encounterId()).isNull();

        long otherUser = insert("INSERT INTO sys_user(username,password_hash,role,status,token_version) VALUES (?,'unused','DOCTOR','ENABLED',0)",
                "management-test-" + UUID.randomUUID());
        long department = scalar("SELECT department_id FROM med_doctor WHERE id=?", doctorId);
        insert("INSERT INTO med_doctor(user_id,department_id,name,status) VALUES (?,?,'另一医生','ENABLED')", otherUser, department);
        assertThat(management.findDoctorPage(otherUser, date, date, null, 1, 20).items()).isEmpty();
        assertThat(management.doctorAppointments(otherUser, date, 1, 20).items()).isEmpty();
    }

    @Test void cancellationWinningSlotLocksPreventsConcurrentBooking() throws Exception {
        long schedule = published();
        long slot = firstSlot(schedule);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // Pause before schedule X acquisition: a booking query must not
            // hold schedule S while waiting for these slots, or this race
            // would expose the actual reverse-order deadlock.
            locked.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("cancel latch timeout");
            return null;
        }).when(repository).lockSlots(anyList());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ScheduleItem> cancellation = pool.submit(() -> management.cancel(schedule, adminId, "医生请假"));
            assertThat(locked.await(8, TimeUnit.SECONDS)).isTrue();
            Future<ErrorCode> appointment = pool.submit(() -> businessCode(() -> book(slot)));
            try {
                assertThat(awaitCondition(() -> lockWait("med_slot"))).as("booking waits for cancellation's slot lock").isTrue();
            } finally { release.countDown(); }
            assertThat(cancellation.get(15, TimeUnit.SECONDS).status()).isEqualTo(ScheduleStatus.CANCELLED);
            assertThat(appointment.get(15, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SLOT_NOT_BOOKABLE);
        } finally { release.countDown(); }
        assertThat(scalar("SELECT COUNT(*) FROM med_appointment")).isZero();
        assertThat(scalar("SELECT cancelled_by FROM med_schedule WHERE id=?", schedule)).isEqualTo(adminId);
        assertThat(scalar("SELECT COUNT(*) FROM med_slot WHERE schedule_id=? AND status='CANCELLED'", schedule)).isEqualTo(8);
    }

    @Test void bookingWinningSlotLockIsSeenByCancellationCurrentRead() throws Exception {
        long schedule = published();
        long slot = firstSlot(schedule);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            locked.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("booking latch timeout");
            return result;
        }).when(booking).lockBookingSnapshot(slot);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ErrorCode> appointment = pool.submit(() -> businessCode(() -> book(slot)));
            assertThat(locked.await(8, TimeUnit.SECONDS)).isTrue();
            Future<ErrorCode> cancellation = pool.submit(() -> businessCode(() -> management.cancel(schedule, adminId, "医生请假")));
            try {
                assertThat(awaitCondition(() -> lockWait("med_slot"))).as("cancellation waits for booking's slot lock").isTrue();
            } finally { release.countDown(); }
            assertThat(appointment.get(15, TimeUnit.SECONDS)).isNull();
            assertThat(cancellation.get(15, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SCHEDULE_HAS_APPOINTMENTS);
        } finally { release.countDown(); }
        assertThat(scalar("SELECT COUNT(*) FROM med_appointment WHERE status='PENDING_CONFIRMATION'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM med_schedule WHERE id=?", String.class, schedule)).isEqualTo("PUBLISHED");
        assertThat(scalar("SELECT remaining_capacity FROM med_slot WHERE id=?", slot)).isEqualTo(9);
    }

    @Test void publishWinningDraftRaceLeavesOpenSlotsAndCancellationReturnsConflict() throws Exception {
        long schedule = draft(date);
        CountDownLatch readDraft = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            readDraft.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("draft latch timeout");
            return result;
        }).when(repository).findState(schedule);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<ErrorCode> cancellation = pool.submit(() -> businessCode(() -> management.cancel(schedule, adminId, "医生请假")));
            try {
                assertThat(readDraft.await(8, TimeUnit.SECONDS)).isTrue();
                schedules.publish(schedule);
            } finally { release.countDown(); }
            assertThat(cancellation.get(15, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SCHEDULE_NOT_CANCELLABLE);
        } finally { release.countDown(); }
        assertThat(scalar("SELECT COUNT(*) FROM med_slot WHERE schedule_id=? AND status='OPEN'", schedule)).isEqualTo(8);
    }

    @Test void repeatedCancellationPreservesOriginalAuditAndFutureDraftCapacityIsVisible() {
        long schedule = draft(date);
        assertThat(management.detail(schedule).schedule().totalCapacity()).isEqualTo(80);
        management.cancel(schedule, adminId, "第一次取消");
        management.cancel(schedule, adminId, "第二次取消");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM med_schedule WHERE id=?", String.class, schedule)).isEqualTo("第一次取消");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM med_schedule WHERE id=?", LocalDateTime.class, schedule)).isNotNull();
    }

    @Test void confirmedAppointmentsAlsoBlockAdministrativeCancellation() {
        long schedule = published();
        long appointment = appointments.create(ownerId, new CreateAppointmentCommand(patientId,
                firstSlot(schedule), "management-" + UUID.randomUUID())).appointmentId();
        appointments.confirm(ownerId, appointment);
        assertThatThrownBy(() -> management.cancel(schedule, adminId, "医生请假"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_HAS_APPOINTMENTS));
        assertThat(management.detail(schedule).schedule().status()).isEqualTo(ScheduleStatus.PUBLISHED);
        assertThat(management.detail(schedule).schedule().activeAppointmentCount()).isEqualTo(1);
    }

    private long draft(LocalDate day) {
        return schedules.createDraft(new CreateScheduleCommand(doctorId, roomId, templateId, day));
    }
    private long published() { long id = draft(date); schedules.publish(id); return id; }
    private long firstSlot(long schedule) { return scalar("SELECT MIN(id) FROM med_slot WHERE schedule_id=?", schedule); }
    private void book(long slot) { appointments.create(ownerId, new CreateAppointmentCommand(patientId, slot, "management-" + UUID.randomUUID())); }
    private ErrorCode businessCode(Runnable action) {
        try { action.run(); return null; }
        catch (BusinessException error) { return error.getErrorCode(); }
    }
    private boolean lockWait(String table) {
        return scalar("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?
                """, table) > 0;
    }
    private boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
    private long scalar(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches()) throw new IllegalStateException("Only the disposable medflow_confirmation_test schema is allowed");
        int port = Integer.parseInt(match.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) throw new IllegalStateException("Disposable MySQL must use a temporary port");
        return url;
    }
}
