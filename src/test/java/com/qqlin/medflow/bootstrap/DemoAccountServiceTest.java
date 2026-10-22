package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.organization.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemoAccountServiceTest {
    @Mock DemoAccountRepository repository;
    @Mock PasswordEncoder passwords;
    DemoAccountService service;

    @BeforeEach void setup() { service = new DemoAccountService(repository, passwords); }

    @Test void unchangedAccountsAndProfilesDoNotChangePasswordHashOrTokenVersion() {
        correctAccounts();
        existingDoctor(ResourceStatus.ENABLED, ResourceStatus.ENABLED);
        service.initialize();
        verify(repository, never()).updateAccount(anyLong(), anyString(), any());
        verify(repository, never()).insertAccountIfAbsent(anyString(), anyString(), any());
        verify(passwords, never()).encode(any());
        verify(repository, never()).repairDoctor(anyLong(), anyLong());
        verify(repository, never()).enableDepartment(anyLong());
    }

    @Test void changingAdminPasswordUsesNewBcryptHashWithoutChangingIdentity() {
        correctAccounts();
        existingDoctor(ResourceStatus.ENABLED, ResourceStatus.ENABLED);
        when(repository.lockAccount("admin")).thenReturn(account(1, "admin", UserRole.ADMIN, UserStatus.ENABLED, "old-hash"));
        when(passwords.matches("123456", "old-hash")).thenReturn(false);
        when(passwords.encode("123456")).thenReturn("new-bcrypt-hash");
        service.initialize();
        verify(repository).updateAccount(1, "new-bcrypt-hash", UserRole.ADMIN);
        verify(repository, never()).updateAccount(eq(2L), anyString(), any());
        verify(repository, never()).updateAccount(eq(3L), anyString(), any());
    }

    @Test void correctingRoleAndStatusReusesMatchingHashAndRevokesOldAuthorityOnce() {
        correctAccounts();
        existingDoctor(ResourceStatus.ENABLED, ResourceStatus.ENABLED);
        when(repository.lockAccount("patient")).thenReturn(account(3, "patient", UserRole.DOCTOR, UserStatus.LOCKED, "correct-hash"));
        service.initialize();
        verify(repository).updateAccount(3, "correct-hash", UserRole.PATIENT);
        verify(passwords, never()).encode(any());
    }

    @Test void missingDoctorGetsItsOwnAccountAndBoundProfile() {
        correctAccounts();
        when(repository.lockAccount("doctor")).thenReturn(null, account(2, "doctor", UserRole.DOCTOR, UserStatus.ENABLED, "correct-hash"));
        when(passwords.encode("123456")).thenReturn("correct-hash");
        when(repository.ensureDemoDepartment("演示门诊")).thenReturn(new Department(10, "演示门诊", ResourceStatus.ENABLED));
        service.initialize();
        verify(repository).insertAccountIfAbsent("doctor", "correct-hash", UserRole.DOCTOR);
        verify(repository).insertDoctor(2, 10, "演示医生");
        verify(repository, never()).updateAccount(anyLong(), anyString(), any());
    }

    @Test void disabledExistingDoctorAndDepartmentAreEnabledWithoutDuplicateProfile() {
        correctAccounts();
        existingDoctor(ResourceStatus.DISABLED, ResourceStatus.DISABLED);
        service.initialize();
        verify(repository).enableDepartment(10);
        verify(repository).repairDoctor(20, 10);
        verify(repository, never()).insertDoctor(anyLong(), anyLong(), anyString());
    }

    @Test void defaultConfigurationDoesNotExposeBootstrapOrService() {
        context().run(context -> {
            assertThat(context).doesNotHaveBean(DemoAccountBootstrap.class);
            assertThat(context).doesNotHaveBean(DemoAccountService.class);
        });
        verifyNoInteractions(repository, passwords);
    }

    @Test void explicitConfigurationEnablesBothBootstrapAndService() {
        context().withPropertyValues("medflow.demo.accounts.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(DemoAccountBootstrap.class);
            assertThat(context).hasSingleBean(DemoAccountService.class);
        });
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DemoAccountBootstrap.class, DemoAccountService.class)
                .withBean(DemoAccountRepository.class, () -> repository)
                .withBean(PasswordEncoder.class, () -> passwords);
    }

    private void correctAccounts() {
        when(repository.lockAccount("admin")).thenReturn(account(1, "admin", UserRole.ADMIN, UserStatus.ENABLED, "correct-hash"));
        when(repository.lockAccount("doctor")).thenReturn(account(2, "doctor", UserRole.DOCTOR, UserStatus.ENABLED, "correct-hash"));
        when(repository.lockAccount("patient")).thenReturn(account(3, "patient", UserRole.PATIENT, UserStatus.ENABLED, "correct-hash"));
        when(passwords.matches("123456", "correct-hash")).thenReturn(true);
    }

    private void existingDoctor(ResourceStatus doctorStatus, ResourceStatus departmentStatus) {
        when(repository.lockDoctorForUser(2)).thenReturn(new Doctor(20, 2, 10, "Existing doctor", doctorStatus));
        when(repository.lockDepartment(10)).thenReturn(new Department(10, "Existing department", departmentStatus));
    }

    private UserAccount account(long id, String username, UserRole role, UserStatus status, String hash) {
        return new UserAccount(id, username, hash, role, status, 4);
    }
}
