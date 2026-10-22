package com.qqlin.medflow.encounter;

import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.encounter.dto.EncounterDetailResponse;
import com.qqlin.medflow.encounter.dto.SaveMedicalRecordRequest;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static com.qqlin.medflow.encounter.domain.EncounterStatus.*;

/** Real MySQL only: refuses the normal medflow database and port 3306. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class EncounterConcurrencyMySqlIT {
    private static final String SCHEMA = "medflow_confirmation_test";
    private static final String URL = isolatedUrl();
    @Autowired EncounterService encounters;
    @Autowired AppointmentService appointments;
    @Autowired JdbcTemplate db;
    long ownerId, doctorUserId, otherDoctorUserId, departmentId, doctorId, otherDoctorId, roomId;
    long patientId, secondPatientId, scheduleId, slotId, appointmentId, secondAppointmentId;
    String trigger;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout = 8");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(SCHEMA);
        String suffix = UUID.randomUUID().toString();
        ownerId = insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?, 'unused', 'PATIENT')", "enc.patient." + suffix);
        doctorUserId = insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?, 'unused', 'DOCTOR')", "enc.doctor." + suffix);
        otherDoctorUserId = insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?, 'unused', 'DOCTOR')", "enc.other." + suffix);
        departmentId = insert("INSERT INTO med_department(name) VALUES(?)", "enc.department." + suffix);
        roomId = insert("INSERT INTO med_clinic_room(department_id,name) VALUES(?, 'Test room')", departmentId);
        doctorId = insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?, ?, 'Test doctor')", doctorUserId, departmentId);
        otherDoctorId = insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?, ?, 'Other doctor')", otherDoctorUserId, departmentId);
        patientId = insertPatient("Test patient");
        secondPatientId = insertPatient("Second patient");
        scheduleId = insert("""
                INSERT INTO med_schedule(doctor_id,clinic_room_id,work_date,start_time,end_time,status)
                VALUES(?, ?, CURRENT_DATE(), '00:00:00', '23:59:59', 'PUBLISHED')
                """, doctorId, roomId);
        slotId = insert("""
                INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity)
                VALUES(?,CURRENT_DATE(),'00:00:00','23:59:59',DATE_ADD(NOW(3),INTERVAL 1 HOUR),10,8)
                """, scheduleId);
        appointmentId = insertAppointment(patientId);
        secondAppointmentId = insertAppointment(secondPatientId);
    }

    @AfterEach void cleanup() {
        if (trigger != null) db.execute("DROP TRIGGER IF EXISTS " + trigger);
        db.update("DELETE l FROM med_encounter_status_log l JOIN med_encounter e ON e.id=l.encounter_id WHERE e.appointment_id IN (?,?)", appointmentId, secondAppointmentId);
        db.update("DELETE r FROM med_medical_record r JOIN med_encounter e ON e.id=r.encounter_id WHERE e.appointment_id IN (?,?)", appointmentId, secondAppointmentId);
        db.update("DELETE FROM med_encounter WHERE appointment_id IN (?,?)", appointmentId, secondAppointmentId);
        db.update("DELETE FROM med_appointment_status_log WHERE appointment_id IN (?,?)", appointmentId, secondAppointmentId);
        db.update("DELETE FROM med_appointment WHERE id IN (?,?)", appointmentId, secondAppointmentId);
        db.update("DELETE FROM med_slot WHERE id=?", slotId);
        db.update("DELETE FROM med_schedule WHERE id=?", scheduleId);
        db.update("DELETE FROM med_patient WHERE id IN (?,?)", patientId, secondPatientId);
        db.update("DELETE FROM med_doctor WHERE id IN (?,?)", doctorId, otherDoctorId);
        db.update("DELETE FROM med_clinic_room WHERE id=?", roomId);
        db.update("DELETE FROM med_department WHERE id=?", departmentId);
        db.update("DELETE FROM sys_user WHERE id IN (?,?,?)", ownerId, doctorUserId, otherDoctorUserId);
    }

    @Test void concurrentDuplicateCheckInCreatesOneEncounterAndOneHistory() throws Exception {
        var outcomes = race(() -> encounters.checkIn(ownerId, appointmentId), () -> encounters.checkIn(ownerId, appointmentId));
        assertThat(outcomes).allMatch(EncounterDetailResponse.class::isInstance);
        var first = (EncounterDetailResponse) outcomes.get(0);
        var second = (EncounterDetailResponse) outcomes.get(1);
        assertThat(first.encounter().id()).isEqualTo(second.encounter().id());
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE appointment_id=?", appointmentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_encounter_status_log WHERE encounter_id=?", first.encounter().id())).isEqualTo(1);
        assertThat(capacity()).isEqualTo(8);
    }

    @Test void concurrentCheckInAndCancellationCannotBothSucceed() throws Exception {
        var outcomes = race(() -> encounters.checkIn(ownerId, appointmentId), () -> appointments.cancel(ownerId, appointmentId));
        assertThat(outcomes.stream().filter(ErrorCode.class::isInstance).count()).isEqualTo(1);
        String appointmentStatus = db.queryForObject("SELECT status FROM med_appointment WHERE id=?", String.class, appointmentId);
        if ("CANCELLED".equals(appointmentStatus)) {
            assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE appointment_id=?", appointmentId)).isZero();
            assertThat(capacity()).isEqualTo(9);
        } else {
            assertThat(appointmentStatus).isEqualTo("BOOKED");
            assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE appointment_id=?", appointmentId)).isEqualTo(1);
            assertThat(capacity()).isEqualTo(8);
            assertThat(outcomes).contains(ErrorCode.APPOINTMENT_ALREADY_CHECKED_IN);
        }
    }

    @Test void concurrentStartsAcrossTwoEncountersAllowOnlyOneInProgress() throws Exception {
        long first = encounters.checkIn(ownerId, appointmentId).encounter().id();
        long second = encounters.checkIn(ownerId, secondAppointmentId).encounter().id();
        var outcomes = race(() -> encounters.start(doctorUserId, first), () -> encounters.start(doctorUserId, second));
        assertThat(outcomes.stream().filter(EncounterDetailResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.DOCTOR_ALREADY_ATTENDING);
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND status='IN_PROGRESS'", doctorId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND status='WAITING'", doctorId)).isEqualTo(1);
    }

    @Test void generatedUniqueKeyRejectsSecondAttendingDoctorEvenWhenServiceIsBypassed() {
        long first = encounters.checkIn(ownerId, appointmentId).encounter().id();
        long second = encounters.checkIn(ownerId, secondAppointmentId).encounter().id();
        encounters.start(doctorUserId, first);
        assertThatThrownBy(() -> db.update("UPDATE med_encounter SET status='IN_PROGRESS',started_at=NOW(3) WHERE id=?", second))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test void sameDraftVersionCanOnlyBeSavedOnceAndCompletedRecordIsReadOnly() throws Exception {
        long id = encounters.checkIn(ownerId, appointmentId).encounter().id();
        encounters.start(doctorUserId, id);
        var outcomes = race(() -> encounters.saveMedicalRecord(doctorUserId, id, draft(0)),
                () -> encounters.saveMedicalRecord(doctorUserId, id, draft(0)));
        assertThat(outcomes.stream().filter(EncounterDetailResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT);
        assertThat(encounters.patientDetail(ownerId, appointmentId).medicalRecord()).isNull();
        assertThatThrownBy(() -> encounters.complete(doctorUserId, id, 0))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT));
        var completed = encounters.complete(doctorUserId, id, 1);
        assertThat(completed.encounter().status()).isEqualTo(COMPLETED);
        assertThat(encounters.complete(doctorUserId, id, 1).history()).hasSize(3);
        assertThat(encounters.patientDetail(ownerId, appointmentId).medicalRecord().chiefComplaint()).isEqualTo("Test complaint");
        assertThatThrownBy(() -> encounters.saveMedicalRecord(doctorUserId, id, draft(1))).isInstanceOf(BusinessException.class);
        assertThat(capacity()).isEqualTo(8);
    }

    @Test void resourceOwnershipHidesOtherDoctorsAndOtherOwnersRecords() {
        long id = encounters.checkIn(ownerId, appointmentId).encounter().id();
        assertThatThrownBy(() -> encounters.doctorDetail(otherDoctorUserId, id))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ENCOUNTER_NOT_FOUND));
        assertThatThrownBy(() -> encounters.start(otherDoctorUserId, id))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ENCOUNTER_NOT_FOUND));
        assertThatThrownBy(() -> encounters.patientDetail(doctorUserId, appointmentId))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ENCOUNTER_NOT_FOUND));
        assertThat(encounters.doctorQueue(otherDoctorUserId, null, null, 1, 20).total()).isZero();
    }

    @Test void statusAndHistoryRollBackTogetherWhenHistoryInsertFails() {
        long id = encounters.checkIn(ownerId, appointmentId).encounter().id();
        trigger = "test_enc_history_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON med_encounter_status_log FOR EACH ROW "
                + "BEGIN IF NEW.encounter_id = " + id + " AND NEW.to_status = 'IN_PROGRESS' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test history failure'; END IF; END");
        assertThatThrownBy(() -> encounters.start(doctorUserId, id)).isInstanceOf(DataAccessException.class);
        assertThat(encounters.doctorDetail(doctorUserId, id).encounter().status()).isEqualTo(WAITING);
        assertThat(encounters.doctorDetail(doctorUserId, id).history()).hasSize(1);
    }

    @Test void checkInRechecksWindowAfterWaitingForSlotLock() throws Exception {
        LocalDateTime now = db.queryForObject("SELECT NOW(3)", LocalDateTime.class);
        Assumptions.assumeTrue(now.getHour() < 23, "Boundary fixture must stay on one calendar day");
        LocalDateTime cutoff = now.plusSeconds(2);
        db.update("UPDATE med_slot SET end_time=? WHERE id=?", cutoff.toLocalTime(), slotId);
        try (Connection lock = db.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try (PreparedStatement statement = lock.prepareStatement("SELECT id FROM med_slot WHERE id=? FOR UPDATE")) {
                statement.setLong(1, slotId);
                try (var ignored = statement.executeQuery()) { /* Keep the slot locked. */ }
            }
            Future<Object> pending = executor.submit(() -> outcome(() -> encounters.checkIn(ownerId, appointmentId)));
            Thread.sleep(3000);
            lock.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.CHECK_IN_NOT_ALLOWED);
        }
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE appointment_id=?", appointmentId)).isZero();
    }

    @Test void skippedEncounterIsTerminalAndKeepsCapacity() {
        long id = encounters.checkIn(ownerId, appointmentId).encounter().id();
        assertThat(encounters.skip(doctorUserId, id).encounter().status()).isEqualTo(SKIPPED);
        assertThat(encounters.skip(doctorUserId, id).history()).hasSize(2);
        assertThatThrownBy(() -> encounters.start(doctorUserId, id)).isInstanceOf(BusinessException.class);
        assertThat(capacity()).isEqualTo(8);
    }

    private SaveMedicalRecordRequest draft(long version) {
        return new SaveMedicalRecordRequest("Test complaint", "Test present illness", "Test diagnosis", "Test advice", version);
    }

    private long insertPatient(String name) {
        return insert("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES(?,?,'UNKNOWN','2000-01-01','13800000000','SELF')
                """, ownerId, name);
    }

    private long insertAppointment(long patient) {
        return insert("""
                INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline)
                VALUES(?,?,'BOOKED',DATE_ADD(NOW(3),INTERVAL 1 HOUR),DATE_ADD(NOW(3),INTERVAL 1 HOUR))
                """, patient, slotId);
    }

    private long insert(String sql, Object... parameters) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private long count(String sql, Object... values) { return db.queryForObject(sql, Long.class, values); }
    private long capacity() { return count("SELECT remaining_capacity FROM med_slot WHERE id=?", slotId); }

    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Object> one = executor.submit(() -> { gate.await(); return outcome(first); });
            Future<Object> two = executor.submit(() -> { gate.await(); return outcome(second); });
            gate.countDown();
            return List.of(one.get(12, TimeUnit.SECONDS), two.get(12, TimeUnit.SECONDS));
        }
    }

    private Object outcome(Supplier<?> action) {
        try { return action.get(); } catch (BusinessException exception) { return exception.getErrorCode(); }
    }

    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/" + SCHEMA + "(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) == 3306 || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalStateException("Use tools/test-confirmation.ps1 with isolated MEDFLOW_CONFIRMATION_TEST_URL; normal database refused");
        }
        return url;
    }
}
