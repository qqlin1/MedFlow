package com.qqlin.medflow.organization.detail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class DoctorDetailRepository {
    private final JdbcTemplate jdbc;
    public DoctorDetailRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<DoctorDetail> findEnabled(long id) {
        return jdbc.query("""
                SELECT d.id,d.department_id,d.name,p.name AS department_name,d.title,d.specialty,d.introduction
                FROM med_doctor d JOIN med_department p ON p.id=d.department_id
                WHERE d.id=? AND d.status='ENABLED' AND p.status='ENABLED'
                """, (rs, row) -> new DoctorDetail(rs.getLong("id"), rs.getLong("department_id"),
                rs.getString("name"), rs.getString("department_name"),rs.getString("title"),
                rs.getString("specialty"),rs.getString("introduction")), id).stream().findFirst();
    }

    public int updateName(long id, String name) {
        return jdbc.update("UPDATE med_doctor SET name=? WHERE id=? AND status='ENABLED'", name, id);
    }

    public int updateProfile(long id, String name, String title, String specialty, String introduction) {
        return jdbc.update("""
                UPDATE med_doctor SET name=?,title=COALESCE(?,title),specialty=COALESCE(?,specialty),
                  introduction=COALESCE(?,introduction) WHERE id=? AND status='ENABLED'
                """,name,title,specialty,introduction,id);
    }

    public java.util.List<DoctorDetail> directory(Long departmentId, String query) {
        // A bounded public catalogue, independent of whether a doctor has a slot today.
        return jdbc.query("""
                SELECT d.id,d.department_id,d.name,p.name AS department_name,d.title,d.specialty,d.introduction
                FROM med_doctor d JOIN med_department p ON p.id=d.department_id
                WHERE d.status='ENABLED' AND p.status='ENABLED' AND (? IS NULL OR d.department_id=?)
                  AND (?='' OR LOCATE(?,d.name)>0 OR LOCATE(?,d.specialty)>0)
                ORDER BY p.name,d.name,d.id LIMIT 200
                """,(rs,row)->new DoctorDetail(rs.getLong("id"),rs.getLong("department_id"),rs.getString("name"),
                rs.getString("department_name"),rs.getString("title"),rs.getString("specialty"),rs.getString("introduction")),
                departmentId,departmentId,query,query,query);
    }
}
