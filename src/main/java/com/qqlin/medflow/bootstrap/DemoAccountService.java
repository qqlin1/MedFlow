package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicitly enabled local demo accounts, separate from normal provisioning rules. */
@Service
@ConditionalOnProperty(name = "medflow.demo.accounts.enabled", havingValue = "true")
public class DemoAccountService {
    static final String DEMO_PASSWORD = "123456";
    static final String DEPARTMENT_NAME = "演示门诊";
    static final String DOCTOR_NAME = "演示医生";
    private final DemoAccountRepository repository;
    private final PasswordEncoder passwords;

    public DemoAccountService(DemoAccountRepository repository, PasswordEncoder passwords) {
        this.repository = repository;
        this.passwords = passwords;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initialize() {
        // Fixed order serializes concurrent startup runs through the admin row.
        ensureAccount("admin", UserRole.ADMIN);
        long doctorUserId = ensureAccount("doctor", UserRole.DOCTOR);
        ensureAccount("patient", UserRole.PATIENT);
        ensureDoctorProfile(doctorUserId);
    }

    private long ensureAccount(String username, UserRole role) {
        UserAccount account = repository.lockAccount(username);
        if (account == null) {
            repository.insertAccountIfAbsent(username, passwords.encode(DEMO_PASSWORD), role);
            account = repository.lockAccount(username);
            if (account == null) throw new IllegalStateException("Demo account provisioning did not create an account");
        }
        boolean passwordMatches = passwords.matches(DEMO_PASSWORD, account.passwordHash());
        if (!passwordMatches || account.role() != role || account.status() != UserStatus.ENABLED) {
            // Changing credentials/authority invalidates existing JWTs exactly once.
            String hash = passwordMatches ? account.passwordHash() : passwords.encode(DEMO_PASSWORD);
            repository.updateAccount(account.id(), hash, role);
        }
        return account.id();
    }

    private void ensureDoctorProfile(long userId) {
        Doctor doctor = repository.lockDoctorForUser(userId);
        Department department = doctor == null ? null : repository.lockDepartment(doctor.departmentId());
        if (department == null) department = repository.ensureDemoDepartment(DEPARTMENT_NAME);
        if (department.status() != ResourceStatus.ENABLED) repository.enableDepartment(department.id());
        if (doctor == null) {
            repository.insertDoctor(userId, department.id(), DOCTOR_NAME);
        } else if (doctor.departmentId() != department.id() || doctor.status() != ResourceStatus.ENABLED) {
            // Keep valid existing names and department bindings rather than duplicate identities.
            repository.repairDoctor(doctor.id(), department.id());
        }
    }
}
