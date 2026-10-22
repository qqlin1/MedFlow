package com.qqlin.medflow.organization.query;

import com.qqlin.medflow.organization.provisioning.DoctorAccountService;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

@Tag("mysql-it")
@SpringBootTest(properties = {"medflow.appointment.expiration.enabled=false", "medflow.demo.accounts.enabled=false"})
class AdminDoctorDirectoryMySqlIT {
    private static final String DATABASE_URL = isolatedUrl();
    @Autowired AdminDoctorDirectoryQuery query;
    @Autowired DoctorAccountService accounts;
    @Autowired JdbcTemplate jdbc;
    long departmentId;
    String username;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("medflow_confirmation_test");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        username = "directory." + suffix;
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO med_department(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "directory-" + suffix);
            return statement;
        }, key);
        departmentId = Objects.requireNonNull(key.getKey()).longValue();
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM med_doctor WHERE department_id=?", departmentId);
        jdbc.update("DELETE FROM sys_user WHERE username=?", username);
        jdbc.update("DELETE FROM med_department WHERE id=?", departmentId);
    }

    @Test void realJoinReturnsUsernameAndRetainsDepartmentAndDoctorEnableChecks() {
        var doctor = accounts.create(username, "Doctor@123", "目录医生", departmentId);
        assertThat(query.findEnabledByDepartment(departmentId)).singleElement().satisfies(row -> {
            assertThat(row.username()).isEqualTo(username);
            assertThat(row.id()).isEqualTo(doctor.id());
            assertThat(row.userId()).isEqualTo(doctor.userId());
            assertThat(row.name()).isEqualTo("目录医生");
        });
        jdbc.update("UPDATE med_doctor SET status='DISABLED' WHERE id=?", doctor.id());
        assertThat(query.findEnabledByDepartment(departmentId)).isEmpty();
        jdbc.update("UPDATE med_department SET status='DISABLED' WHERE id=?", departmentId);
        assertThatThrownBy(() -> query.findEnabledByDepartment(departmentId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DEPARTMENT_DISABLED));
    }

    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$")
                .matcher(url == null ? "" : url);
        if (!match.matches()) throw new IllegalStateException("Only disposable medflow_confirmation_test is allowed");
        int port = Integer.parseInt(match.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) throw new IllegalStateException("Temporary non-default MySQL port required");
        return url;
    }
}
