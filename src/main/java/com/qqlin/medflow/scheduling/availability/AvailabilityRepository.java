package com.qqlin.medflow.scheduling.availability;

import com.qqlin.medflow.scheduling.availability.AvailabilityModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AvailabilityRepository {
    private static final String OPEN_FUTURE = """
            sl.status='OPEN' AND sl.booking_deadline>clock.at_time
            AND TIMESTAMP(sl.work_date, sl.start_time)>clock.at_time
            """;
    private static final String AVAILABLE = OPEN_FUTURE + " AND sl.remaining_capacity>0 ";
    private static final String RESOURCES = """
            FROM med_schedule sch
            INNER JOIN med_doctor d ON d.id=sch.doctor_id
            INNER JOIN med_department dep ON dep.id=d.department_id
            INNER JOIN med_clinic_room room ON room.id=sch.clinic_room_id
            LEFT JOIN med_slot sl ON sl.schedule_id=sch.id AND sl.work_date=sch.work_date
            CROSS JOIN (SELECT CAST(? AS DATETIME(3)) AS at_time) clock
            WHERE sch.status='PUBLISHED' AND d.status='ENABLED' AND dep.status='ENABLED'
              AND room.status='ENABLED' AND room.department_id=d.department_id
            """;

    private final JdbcTemplate jdbc;
    public AvailabilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public LocalDateTime databaseNow() {
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
        if (now == null) throw new IllegalStateException("数据库时间读取失败");
        return now;
    }

    public Optional<String> departmentStatus(long id) {
        return jdbc.query("SELECT status FROM med_department WHERE id=?", (rs, row) -> rs.getString(1), id)
                .stream().findFirst();
    }

    public Optional<DoctorScope> doctorScope(long id) {
        return jdbc.query("""
                SELECT d.department_id, d.status AS doctor_status, dep.status AS department_status
                FROM med_doctor d INNER JOIN med_department dep ON dep.id=d.department_id WHERE d.id=?
                """, (rs, row) -> new DoctorScope(rs.getLong("department_id"), rs.getString("doctor_status"),
                rs.getString("department_status")), id).stream().findFirst();
    }

    public List<DayAggregate> days(LocalDate from, LocalDate to, Long departmentId, Long doctorId, LocalDateTime now) {
        List<Object> parameters = new ArrayList<>(List.of(Timestamp.valueOf(now), Date.valueOf(from), Date.valueOf(to)));
        String scope = scope(departmentId, doctorId, parameters);
        String sql = "SELECT sch.work_date, COUNT(DISTINCT sch.id) AS schedule_count, "
                + "SUM(CASE WHEN " + OPEN_FUTURE + " THEN 1 ELSE 0 END) AS open_count, "
                + "SUM(CASE WHEN " + AVAILABLE + " THEN 1 ELSE 0 END) AS available_count, "
                + "SUM(CASE WHEN " + AVAILABLE + " THEN sl.remaining_capacity ELSE 0 END) AS remaining_capacity, "
                + "COUNT(DISTINCT CASE WHEN " + AVAILABLE + " THEN d.id END) AS doctor_count "
                + RESOURCES + " AND sch.work_date BETWEEN ? AND ? " + scope + " GROUP BY sch.work_date ORDER BY sch.work_date";
        return jdbc.query(sql, (rs, row) -> new DayAggregate(rs.getDate("work_date").toLocalDate(),
                rs.getLong("schedule_count"), rs.getLong("open_count"), rs.getLong("available_count"),
                rs.getLong("remaining_capacity"), rs.getLong("doctor_count")), parameters.toArray());
    }

    public List<AvailableDoctor> doctors(long departmentId, LocalDate workDate, LocalDateTime now) {
        return jdbc.query("""
                SELECT d.id, d.department_id, d.name, dep.name AS department_name,
                    d.title, d.specialty, d.introduction,
                    COUNT(sl.id) AS available_slot_count, SUM(sl.remaining_capacity) AS remaining_capacity
                """ + RESOURCES + " AND sch.work_date=? AND d.department_id=? AND " + AVAILABLE
                + " GROUP BY d.id, dep.id ORDER BY d.name, d.id", (rs, row) -> new AvailableDoctor(
                rs.getLong("id"), rs.getLong("department_id"), rs.getString("name"), rs.getString("department_name"),
                rs.getString("title"), rs.getString("specialty"), rs.getString("introduction"),
                rs.getLong("available_slot_count"), rs.getLong("remaining_capacity")),
                Timestamp.valueOf(now), Date.valueOf(workDate), departmentId);
    }

    private static String scope(Long departmentId, Long doctorId, List<Object> parameters) {
        String sql = "";
        if (departmentId != null) { sql += " AND d.department_id=? "; parameters.add(departmentId); }
        if (doctorId != null) { sql += " AND d.id=? "; parameters.add(doctorId); }
        return sql;
    }
}
