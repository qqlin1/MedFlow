package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

/** Mutable fixtures restricted to the disposable expiration-comparison schema. */
final class ExpirationComparisonFixture {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";

    private final JdbcTemplate jdbc;
    private final AppointmentService service;
    private long ownerUserId;
    private long slotId;
    private String suffix;
    private int patientSequence;

    ExpirationComparisonFixture(JdbcTemplate jdbc, AppointmentService service) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.service = Objects.requireNonNull(service, "service");
        requireTestSchema();
    }

    void clean() {
        requireTestSchema();
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_confirmation_history");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_expiration_comparison_history");
        jdbc.update("DELETE FROM med_appointment_status_log");
        jdbc.update("DELETE FROM sys_idempotency_record");
        jdbc.update("DELETE FROM med_appointment");
        jdbc.update("DELETE FROM med_slot");
        jdbc.update("DELETE FROM med_schedule");
        jdbc.update("DELETE FROM med_patient");
        jdbc.update("DELETE FROM med_doctor");
        jdbc.update("DELETE FROM med_clinic_room");
        jdbc.update("DELETE FROM med_department");
        ownerUserId = 0;
        slotId = 0;
        suffix = null;
        patientSequence = 0;
    }

    void seed() {
        requireTestSchema();
        if (slotId != 0) {
            throw new IllegalStateException("Clean the existing fixture before seeding again");
        }
        ownerUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = 'patient.li'"
        );
        long doctorUserId = requiredLong(
                "SELECT id FROM sys_user WHERE username = 'doctor.wang'"
        );
        long templateId = requiredLong(
                "SELECT id FROM med_shift_template WHERE code = 'MORNING'"
        );
        suffix = UUID.randomUUID().toString().replace("-", "");
        long departmentId = insertAndGetId("""
                INSERT INTO med_department (name, status)
                VALUES (?, 'ENABLED')
                """, "comparison-department-" + suffix);
        long doctorId = insertAndGetId("""
                INSERT INTO med_doctor (user_id, department_id, name, status)
                VALUES (?, ?, ?, 'ENABLED')
                """, doctorUserId, departmentId, "comparison-doctor-" + suffix);
        long roomId = insertAndGetId("""
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, "comparison-room-" + suffix);
        long scheduleId = insertAndGetId("""
                INSERT INTO med_schedule (
                    doctor_id, clinic_room_id, shift_template_id, work_date,
                    start_time, end_time, slot_duration_minutes,
                    capacity_per_slot, status
                ) VALUES (?, ?, ?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00', '09:30:00', 30, 10, 'PUBLISHED')
                """, doctorId, roomId, templateId);
        slotId = insertAndGetId("""
                INSERT INTO med_slot (
                    schedule_id, work_date, start_time, end_time, booking_deadline,
                    total_capacity, remaining_capacity, status
                ) VALUES (?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00', '09:30:00',
                    DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY), 10, 10, 'OPEN')
                """, scheduleId);
    }

    long createPending() {
        requireSeeded();
        long patientId = insertAndGetId("""
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date, phone, relationship
                ) VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, ownerUserId, "comparison-patient-" + suffix + "-" + ++patientSequence);
        return service.create(ownerUserId, new CreateAppointmentCommand(
                patientId, slotId, "comparison-create-" + UUID.randomUUID()
        )).appointmentId();
    }

    long slotId() {
        requireSeeded();
        return slotId;
    }

    void setDue(long id) {
        requireOneUpdated(jdbc.update("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, id));
    }

    void setDeadlineAfterMillis(long id, int millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative");
        }
        requireOneUpdated(jdbc.update("""
                UPDATE med_appointment
                SET confirm_deadline = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3))
                WHERE id = ?
                """, Math.multiplyExact((long) millis, 1_000L), id));
    }

    String status(long id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM med_appointment WHERE id = ?", String.class, id
        ));
    }

    long capacity() {
        requireSeeded();
        return requiredLong(
                "SELECT remaining_capacity FROM med_slot WHERE id = ?", slotId
        );
    }

    long expiredLogCount(long id) {
        return requiredLong("""
                SELECT COUNT(*) FROM med_appointment_status_log
                WHERE appointment_id = ? AND event_type = 'EXPIRED'
                """, id);
    }

    long databaseMillisUntilDeadline(long id) {
        // A negative value proves that the database deadline has passed.
        return requiredLong("""
                SELECT CEIL(TIMESTAMPDIFF(
                    MICROSECOND, CURRENT_TIMESTAMP(3), confirm_deadline
                ) / 1000.0)
                FROM med_appointment WHERE id = ?
                """, id);
    }

    private void requireTestSchema() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!TEST_SCHEMA.equals(schema)) {
            throw new IllegalStateException(
                    "Expiration comparison is restricted to " + TEST_SCHEMA
            );
        }
    }

    private void requireSeeded() {
        if (slotId <= 0 || ownerUserId <= 0 || suffix == null) {
            throw new IllegalStateException("Seed the expiration fixture first");
        }
    }

    private long requiredLong(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private long insertAndGetId(String sql, Object... arguments) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        int affectedRows = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, holder);
        requireOneUpdated(affectedRows);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private void requireOneUpdated(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected one affected fixture row, got " + affectedRows);
        }
    }
}
