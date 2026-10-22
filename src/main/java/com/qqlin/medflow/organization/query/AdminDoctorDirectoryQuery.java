package com.qqlin.medflow.organization.query;

import com.qqlin.medflow.organization.domain.ResourceStatus;
import com.qqlin.medflow.organization.dto.DoctorResponse;
import com.qqlin.medflow.organization.repository.OrganizationRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Administrative identity display; patient-facing doctor DTOs remain separate. */
@Component
public class AdminDoctorDirectoryQuery {
    private final OrganizationRepository organization;
    private final JdbcTemplate jdbc;
    public AdminDoctorDirectoryQuery(OrganizationRepository organization, JdbcTemplate jdbc) {
        this.organization = organization;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> findEnabledByDepartment(long departmentId) {
        if (departmentId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var department = organization.findDepartmentById(departmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
        if (department.status() != ResourceStatus.ENABLED) throw new BusinessException(ErrorCode.DEPARTMENT_DISABLED);
        return jdbc.query("""
                SELECT d.id, d.user_id, d.department_id, d.name, d.status, u.username
                FROM med_doctor d LEFT JOIN sys_user u ON u.id=d.user_id
                WHERE d.department_id=? AND d.status='ENABLED' ORDER BY d.name, d.id
                """, (rs, row) -> new DoctorResponse(rs.getLong("id"), rs.getLong("user_id"),
                rs.getLong("department_id"), rs.getString("name"), ResourceStatus.valueOf(rs.getString("status")),
                rs.getString("username")), departmentId);
    }
}
