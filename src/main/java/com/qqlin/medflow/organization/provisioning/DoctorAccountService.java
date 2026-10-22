package com.qqlin.medflow.organization.provisioning;

import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import com.qqlin.medflow.organization.repository.OrganizationRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

@Service
public class DoctorAccountService {
    private final DoctorAccountMapper mapper;
    private final UserRepository users;
    private final OrganizationRepository organization;
    private final PasswordEncoder passwords;

    public DoctorAccountService(DoctorAccountMapper mapper, UserRepository users,
                                OrganizationRepository organization, PasswordEncoder passwords) {
        this.mapper = mapper; this.users = users; this.organization = organization; this.passwords = passwords;
    }

    @Transactional
    public Doctor create(String username, String password, String name, long departmentId) {
        if (username == null || username.isBlank() || username.trim().length() > 64
                || name == null || name.isBlank() || name.trim().length() > 64
                || password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || departmentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var department = organization.findDepartmentById(departmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
        if (department.status() != ResourceStatus.ENABLED) throw new BusinessException(ErrorCode.DEPARTMENT_DISABLED);
        String accountName = username.trim();
        try {
            mapper.insert(accountName, passwords.encode(password));
            long userId = users.findByUsername(accountName).orElseThrow().id();
            long doctorId = organization.insertDoctor(userId, departmentId, name.trim());
            return new Doctor(doctorId, userId, departmentId, name.trim(), ResourceStatus.ENABLED);
        } catch (DuplicateKeyException ex) {
            // Escape the transactional boundary: neither an orphan account nor a partial profile may commit.
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }
}
