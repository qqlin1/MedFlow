package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Local demo provisioning only; historical account migrations stay immutable. */
@Repository
public class DemoAccountRepository {
    private final JdbcTemplate jdbc;

    public DemoAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UserAccount lockAccount(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, status, token_version
                FROM sys_user WHERE username = ? FOR UPDATE
                """, (rs, row) -> new UserAccount(rs.getLong("id"), rs.getString("username"),
                rs.getString("password_hash"), UserRole.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")), rs.getInt("token_version")), username)
                .stream().findFirst().orElse(null);
    }

    public void insertAccountIfAbsent(String username, String hash, UserRole role) {
        jdbc.update("""
                INSERT INTO sys_user(username,password_hash,role,status,token_version)
                VALUES(?,?,?,'ENABLED',0) ON DUPLICATE KEY UPDATE id = id
                """, username, hash, role.name());
    }

    public void updateAccount(long id, String hash, UserRole role) {
        jdbc.update("""
                UPDATE sys_user SET password_hash=?,role=?,status='ENABLED',token_version=token_version+1
                WHERE id=?
                """, hash, role.name(), id);
    }

    public Doctor lockDoctorForUser(long userId) {
        return jdbc.query("""
                SELECT id,user_id,department_id,name,status FROM med_doctor WHERE user_id=? FOR UPDATE
                """, (rs, row) -> new Doctor(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("department_id"),
                rs.getString("name"), ResourceStatus.valueOf(rs.getString("status"))), userId)
                .stream().findFirst().orElse(null);
    }

    public Department lockDepartment(long id) {
        return jdbc.query("SELECT id,name,status FROM med_department WHERE id=? FOR UPDATE",
                (rs, row) -> new Department(rs.getLong("id"), rs.getString("name"),
                        ResourceStatus.valueOf(rs.getString("status"))), id)
                .stream().findFirst().orElse(null);
    }

    public Department ensureDemoDepartment(String name) {
        jdbc.update("INSERT INTO med_department(name,status) VALUES(?,'ENABLED') ON DUPLICATE KEY UPDATE id=id", name);
        return jdbc.query("SELECT id,name,status FROM med_department WHERE name=? FOR UPDATE",
                (rs, row) -> new Department(rs.getLong("id"), rs.getString("name"),
                        ResourceStatus.valueOf(rs.getString("status"))), name).getFirst();
    }

    public void enableDepartment(long id) {
        jdbc.update("UPDATE med_department SET status='ENABLED' WHERE id=? AND status<>'ENABLED'", id);
    }

    public void insertDoctor(long userId, long departmentId, String name) {
        jdbc.update("INSERT INTO med_doctor(user_id,department_id,name,status) VALUES(?,?,?,'ENABLED')", userId, departmentId, name);
    }

    public void repairDoctor(long id, long departmentId) {
        jdbc.update("UPDATE med_doctor SET department_id=?,status='ENABLED' WHERE id=?", departmentId, id);
    }
}
