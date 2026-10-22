package com.qqlin.medflow.organization.provisioning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No test transaction: rollback must happen at the real service proxy boundary. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class DoctorAccountMySqlIT {
    private static final String DATABASE_URL = isolatedUrl();
    @Autowired DoctorAccountService service;
    @Autowired JdbcTemplate jdbc;
    long departmentId;
    String username;
    String doctorName;
    String triggerName;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("medflow_confirmation_test");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        username = "rollback.doctor." + suffix;
        doctorName = "rollback-profile-" + suffix;
        triggerName = "test_provision_fail_" + suffix;
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO med_department(name,status) VALUES (?, 'ENABLED')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "rollback-department-" + suffix);
            return statement;
        }, key);
        departmentId = Objects.requireNonNull(key.getKey()).longValue();
    }

    @AfterEach void cleanup() {
        dropTrigger();
        jdbc.update("DELETE FROM med_doctor WHERE name=? AND department_id=?", doctorName, departmentId);
        jdbc.update("DELETE FROM sys_user WHERE username=?", username);
        jdbc.update("DELETE FROM med_department WHERE id=?", departmentId);
    }

    @Test void profileFailureAfterAccountInsertRollsBackBothRows() {
        // The trigger explicitly observes the just-created user in this same
        // transaction before failing the later profile statement. A username
        // uniqueness failure alone would never exercise this rollback path.
        jdbc.execute("CREATE TRIGGER " + triggerName
                + " BEFORE INSERT ON med_doctor FOR EACH ROW BEGIN "
                + "IF NEW.name='" + doctorName + "' THEN "
                + "IF EXISTS(SELECT 1 FROM sys_user WHERE id=NEW.user_id AND username='" + username + "') THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test profile failure after observing account'; "
                + "ELSE SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test missing account before profile'; "
                + "END IF; END IF; END");
        try {
            assertThatThrownBy(() -> service.create(username, "Doctor@123", doctorName, departmentId))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("test profile failure after observing account");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Long.class, username))
                    .as("account inserted before the profile failure must be rolled back").isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_doctor WHERE name=? AND department_id=?",
                    Long.class, doctorName, departmentId))
                    .as("no partial profile may remain").isZero();
        } finally {
            dropTrigger();
        }
    }

    private void dropTrigger() {
        if (triggerName != null) jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName);
    }

    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches()) throw new IllegalStateException("Only disposable medflow_confirmation_test is allowed");
        int port = Integer.parseInt(match.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException("Disposable MySQL must use a temporary non-default port");
        }
        return url;
    }
}
