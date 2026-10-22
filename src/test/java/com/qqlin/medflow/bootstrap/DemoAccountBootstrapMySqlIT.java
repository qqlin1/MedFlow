package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs only in the disposable loopback MySQL created by tools/test-confirmation.ps1. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = {"medflow.demo.accounts.enabled=true", "medflow.appointment.expiration.enabled=false"})
class DemoAccountBootstrapMySqlIT {
    private static final String URL = isolatedUrl();
    @Autowired DemoAccountService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void ensureInitialized() {
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("medflow_confirmation_test");
        service.initialize();
    }

    @AfterEach void restoreDemoAccounts() { service.initialize(); }

    @Test void startupCreatesThreeDistinctAccountsWithBcryptAndCorrectRoles() {
        var roles = Map.of("admin", UserRole.ADMIN, "doctor", UserRole.DOCTOR, "patient", UserRole.PATIENT);
        for (var entry : roles.entrySet()) {
            UserAccount account = users.findByUsername(entry.getKey()).orElseThrow();
            assertThat(account.role()).isEqualTo(entry.getValue());
            assertThat(account.status()).isEqualTo(UserStatus.ENABLED);
            assertThat(account.passwordHash()).startsWith("$2");
            assertThat(account.passwordHash()).isNotEqualTo("123456");
            assertThat(passwords.matches("123456", account.passwordHash())).isTrue();
        }
        assertThat(users.findByUsername("admin").orElseThrow().id())
                .isNotEqualTo(users.findByUsername("doctor").orElseThrow().id())
                .isNotEqualTo(users.findByUsername("patient").orElseThrow().id());
    }

    @Test void repeatInitializationKeepsIdsHashesVersionsAndDoctorProfile() {
        UserAccount admin = users.findByUsername("admin").orElseThrow();
        UserAccount doctor = users.findByUsername("doctor").orElseThrow();
        UserAccount patient = users.findByUsername("patient").orElseThrow();
        var profile = db.queryForMap("SELECT id,department_id,name,status FROM med_doctor WHERE user_id=?", doctor.id());
        service.initialize();
        service.initialize();
        assertThat(users.findByUsername("admin").orElseThrow()).isEqualTo(admin);
        assertThat(users.findByUsername("doctor").orElseThrow()).isEqualTo(doctor);
        assertThat(users.findByUsername("patient").orElseThrow()).isEqualTo(patient);
        assertThat(db.queryForMap("SELECT id,department_id,name,status FROM med_doctor WHERE user_id=?", doctor.id())).isEqualTo(profile);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM med_doctor WHERE user_id=?", Long.class, doctor.id())).isEqualTo(1L);
    }

    @Test void correctingCredentialsRoleAndStatusInvalidatesTokensExactlyOnce() {
        UserAccount original = users.findByUsername("patient").orElseThrow();
        db.update("UPDATE sys_user SET password_hash=?,role='ADMIN',status='LOCKED' WHERE id=?", passwords.encode("other-password"), original.id());
        service.initialize();
        UserAccount repaired = users.findByUsername("patient").orElseThrow();
        assertThat(repaired.id()).isEqualTo(original.id());
        assertThat(repaired.role()).isEqualTo(UserRole.PATIENT);
        assertThat(repaired.status()).isEqualTo(UserStatus.ENABLED);
        assertThat(passwords.matches("123456", repaired.passwordHash())).isTrue();
        assertThat(repaired.tokenVersion()).isEqualTo(original.tokenVersion() + 1);
        service.initialize();
        assertThat(users.findByUsername("patient").orElseThrow()).isEqualTo(repaired);
    }

    @Test void doctorIsBoundToEnabledDepartmentAndProfileAndPatientHasNoDoctorIdentity() {
        long doctorId = users.findByUsername("doctor").orElseThrow().id();
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM med_doctor d JOIN med_department dep ON dep.id=d.department_id
                WHERE d.user_id=? AND d.status='ENABLED' AND dep.status='ENABLED'
                """, Long.class, doctorId)).isEqualTo(1L);
        long patientId = users.findByUsername("patient").orElseThrow().id();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM med_doctor WHERE user_id=?", Long.class, patientId)).isZero();
    }

    @Test void disabledDoctorProfileIsRepairedWithoutCreatingAnotherIdentity() {
        long userId = users.findByUsername("doctor").orElseThrow().id();
        var before = db.queryForMap("SELECT id,department_id FROM med_doctor WHERE user_id=?", userId);
        db.update("UPDATE med_doctor SET status='DISABLED' WHERE user_id=?", userId);
        db.update("UPDATE med_department SET status='DISABLED' WHERE id=?", before.get("department_id"));
        service.initialize();
        assertThat(db.queryForMap("SELECT id,department_id FROM med_doctor WHERE user_id=?", userId)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT status FROM med_doctor WHERE user_id=?", String.class, userId)).isEqualTo("ENABLED");
        assertThat(db.queryForObject("SELECT status FROM med_department WHERE id=?", String.class, before.get("department_id"))).isEqualTo("ENABLED");
    }

    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(1)) == 3306 || Integer.parseInt(match.group(1)) > 65535) {
            throw new IllegalStateException("Refusing normal database; use tools/test-confirmation.ps1 with isolated MEDFLOW_CONFIRMATION_TEST_URL");
        }
        return url;
    }
}
