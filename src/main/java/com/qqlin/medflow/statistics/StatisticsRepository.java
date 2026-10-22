package com.qqlin.medflow.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.qqlin.medflow.statistics.StatisticsResponse.*;

@Repository
public class StatisticsRepository {
    private final JdbcTemplate jdbc;

    public StatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public LocalDate today() {
        return jdbc.queryForObject("SELECT CURRENT_DATE", java.sql.Date.class).toLocalDate();
    }

    public List<AppointmentPoint> appointmentTrend(LocalDateTime from, LocalDateTime until) {
        return jdbc.query("""
                SELECT DATE(created_at) AS day, COUNT(*) AS total
                FROM med_appointment WHERE created_at >= ? AND created_at < ?
                GROUP BY DATE(created_at) ORDER BY day
                """, (rs, row) -> new AppointmentPoint(rs.getDate("day").toLocalDate(), rs.getLong("total")), from, until);
    }

    public List<DepartmentVisits> departmentVisits(LocalDateTime from, LocalDateTime until) {
        return jdbc.query("""
                SELECT dep.id, dep.name, COUNT(*) AS total
                FROM med_encounter e JOIN med_doctor d ON d.id=e.doctor_id
                JOIN med_department dep ON dep.id=d.department_id
                WHERE e.status='COMPLETED' AND e.completed_at >= ? AND e.completed_at < ?
                GROUP BY dep.id, dep.name ORDER BY total DESC, dep.id
                """, (rs, row) -> new DepartmentVisits(rs.getLong("id"), rs.getString("name"), rs.getLong("total")), from, until);
    }

    public List<DoctorWorkload> doctorWorkload(LocalDateTime from, LocalDateTime until) {
        return jdbc.query("""
                SELECT d.id, d.name, dep.name AS department_name, COUNT(*) AS total
                FROM med_encounter e JOIN med_doctor d ON d.id=e.doctor_id
                JOIN med_department dep ON dep.id=d.department_id
                WHERE e.status='COMPLETED' AND e.completed_at >= ? AND e.completed_at < ?
                GROUP BY d.id, d.name, dep.name ORDER BY total DESC, d.id
                """, (rs, row) -> new DoctorWorkload(rs.getLong("id"), rs.getString("name"),
                rs.getString("department_name"), rs.getLong("total")), from, until);
    }
}
