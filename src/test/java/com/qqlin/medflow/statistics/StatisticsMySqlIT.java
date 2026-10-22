package com.qqlin.medflow.statistics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.qqlin.medflow.statistics.StatisticsResponse.*;
import static org.assertj.core.api.Assertions.*;

/** Exact calendar boundaries against a disposable MySQL schema, using distant dates. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = {"medflow.appointment.expiration.enabled=false", "medflow.demo.accounts.enabled=false"})
class StatisticsMySqlIT {
    private static final String DATABASE_URL = isolatedUrl();
    private static final LocalDate FROM = LocalDate.of(2063, 7, 14);
    private static final LocalDate TO = FROM.plusDays(2);
    @Autowired StatisticsService service;
    @Autowired JdbcTemplate jdbc;
    final List<Long> users = new ArrayList<>(), departments = new ArrayList<>(), doctors = new ArrayList<>();
    final List<Long> rooms = new ArrayList<>(), schedules = new ArrayList<>(), slots = new ArrayList<>();
    final List<Long> patients = new ArrayList<>(), appointments = new ArrayList<>(), encounters = new ArrayList<>();
    final Map<Long, Long> slotByDoctor = new HashMap<>();
    long owner;
    LocalDate workDate;
    String prefix;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("medflow_confirmation_test");
        prefix = "stats-" + UUID.randomUUID().toString().replace("-", "");
        owner = account(prefix + "-patient", "PATIENT");
        departments.add(insert("INSERT INTO med_department(name) VALUES (?)", prefix + "-A"));
        departments.add(insert("INSERT INTO med_department(name) VALUES (?)", prefix + "-B"));
        workDate = FROM.plusDays(30);
        for (int index = 0; index < 3; index++) {
            long user = account(prefix + "-doctor" + index, "DOCTOR");
            long department = departments.get(index == 2 ? 1 : 0);
            long doctor = insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES (?,?,?)", user, department, prefix + "-D" + index);
            doctors.add(doctor);
            long room = insert("INSERT INTO med_clinic_room(department_id,name) VALUES (?,?)", department, "Stats room " + index);
            rooms.add(room);
            long schedule = insert("""
                    INSERT INTO med_schedule(doctor_id,clinic_room_id,work_date,start_time,end_time,status)
                    VALUES (?, ?, ?, '08:00:00', '12:00:00', 'PUBLISHED')
                    """, doctor, room, workDate);
            schedules.add(schedule);
            long slot = insert("""
                    INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity)
                    VALUES (?,?,'08:00:00','12:00:00',?,100,100)
                    """, schedule, workDate, workDate.atTime(8, 0));
            slots.add(slot);
            slotByDoctor.put(doctor, slot);
        }
    }

    @AfterEach void cleanup() {
        // Every deletion is restricted to generated IDs owned by this fixture.
        deleteRows("med_encounter", encounters);
        deleteRows("med_appointment", appointments);
        deleteRows("med_patient", patients);
        deleteRows("med_slot", slots);
        deleteRows("med_schedule", schedules);
        deleteRows("med_doctor", doctors);
        deleteRows("med_clinic_room", rooms);
        deleteRows("med_department", departments);
        deleteRows("sys_user", users);
    }

    @Test void appointmentTrendUsesCreationDateIncludesCancelledExpiredAndFillsZeroDays() {
        long doctor = doctors.getFirst();
        appointment(doctor, "PENDING_CONFIRMATION", FROM.atStartOfDay());
        appointment(doctor, "CANCELLED", TO.atTime(10, 0));
        appointment(doctor, "EXPIRED", TO.atTime(11, 0));
        appointment(doctor, "BOOKED", TO.atTime(23, 59, 59, 999_000_000));
        appointment(doctor, "BOOKED", FROM.atStartOfDay().minusNanos(1_000_000));
        appointment(doctor, "BOOKED", TO.plusDays(1).atStartOfDay());

        var result = service.get(FROM, TO);
        assertThat(result.appointmentTrend()).containsExactly(
                new AppointmentPoint(FROM, 1), new AppointmentPoint(FROM.plusDays(1), 0), new AppointmentPoint(TO, 3));
        assertThat(result.departmentVisits()).isEmpty();
        assertThat(result.doctorWorkload()).isEmpty();
        assertThat(workDate).isNotEqualTo(FROM).isNotEqualTo(TO);
    }

    @Test void visitsUseOnlyCompletedAtWithHalfOpenBoundsRatherThanAppointmentOrWorkDate() {
        // A long-running historical encounter may be completed after its work
        // date. Statistics must attribute that completion to completed_at.
        workDate = FROM.minusDays(30);
        schedules.forEach(id -> jdbc.update("UPDATE med_schedule SET work_date=? WHERE id=?", workDate, id));
        slots.forEach(id -> jdbc.update("UPDATE med_slot SET work_date=?,booking_deadline=? WHERE id=?", workDate, workDate.atTime(8, 0), id));
        long a = doctors.get(0), b = doctors.get(1), c = doctors.get(2);
        encounter(a, "COMPLETED", FROM.atStartOfDay());
        encounter(a, "COMPLETED", TO.atTime(23, 59, 59, 999_000_000));
        encounter(b, "COMPLETED", FROM.plusDays(1).atTime(12, 0));
        encounter(c, "COMPLETED", TO.atTime(12, 0));
        encounter(a, "COMPLETED", FROM.atStartOfDay().minusNanos(1_000_000));
        encounter(b, "COMPLETED", TO.plusDays(1).atStartOfDay());
        encounter(a, "WAITING", null);
        encounter(b, "IN_PROGRESS", null);
        encounter(c, "SKIPPED", null);

        var result = service.get(FROM, TO);
        assertThat(result.departmentVisits()).containsExactly(
                new DepartmentVisits(departments.get(0), prefix + "-A", 3),
                new DepartmentVisits(departments.get(1), prefix + "-B", 1));
        assertThat(result.doctorWorkload()).containsExactly(
                new DoctorWorkload(a, prefix + "-D0", prefix + "-A", 2),
                new DoctorWorkload(b, prefix + "-D1", prefix + "-A", 1),
                new DoctorWorkload(c, prefix + "-D2", prefix + "-B", 1));
        assertThat(result.appointmentTrend()).extracting(AppointmentPoint::count).containsExactly(0L, 0L, 0L);
        // Looking at the appointment work date instead would miss all four
        // completions that belong in the requested interval.
        assertThat(service.get(workDate, workDate).departmentVisits()).isEmpty();
    }

    @Test void fullyEmptyCalendarStillReturnsEveryRequestedDateAndNoInventedWorkload() {
        var result = service.get(FROM, TO);
        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.appointmentTrend()).containsExactly(
                new AppointmentPoint(FROM, 0), new AppointmentPoint(FROM.plusDays(1), 0), new AppointmentPoint(TO, 0));
        assertThat(result.departmentVisits()).isEmpty();
        assertThat(result.doctorWorkload()).isEmpty();
    }

    private long account(String username, String role) {
        long id = insert("INSERT INTO sys_user(username,password_hash,role) VALUES (?,'unused',?)", username, role);
        users.add(id);
        return id;
    }
    private long appointment(long doctor, String status, LocalDateTime createdAt) {
        long patient = insert("""
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship)
                VALUES (?,?,'UNKNOWN','2000-01-01','13800000000','SELF')
                """, owner, "Stats patient " + patients.size());
        patients.add(patient);
        long id = insert("""
                INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline,created_at)
                VALUES (?,?,?,?,?,?)
                """, patient, slotByDoctor.get(doctor), status, workDate.atTime(7, 0), workDate.atTime(8, 0), createdAt);
        appointments.add(id);
        return id;
    }
    private void encounter(long doctor, String status, LocalDateTime completedAt) {
        long appointment = appointment(doctor, "BOOKED", workDate.minusDays(1).atTime(12, 0));
        LocalDateTime startedAt = status.equals("IN_PROGRESS") || status.equals("COMPLETED") ? workDate.atTime(8, 0) : null;
        encounters.add(insert("""
                INSERT INTO med_encounter(appointment_id,doctor_id,status,checked_in_at,started_at,completed_at)
                VALUES (?,?,?,?,?,?)
                """, appointment, doctor, status, workDate.atTime(7, 45), startedAt, completedAt));
    }
    private void deleteRows(String table, List<Long> ids) { ids.forEach(id -> jdbc.update("DELETE FROM " + table + " WHERE id=?", id)); }
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
        var match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches()) throw new IllegalStateException("Only disposable medflow_confirmation_test is allowed");
        int port = Integer.parseInt(match.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) throw new IllegalStateException("Temporary non-default MySQL port required");
        return url;
    }
}
