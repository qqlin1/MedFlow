package com.qqlin.medflow.medication.service;

import com.qqlin.medflow.medication.domain.*;
import com.qqlin.medflow.medication.dto.*;
import com.qqlin.medflow.medication.mapper.DrugMapper;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** Real catalog constraints and version races, never the normal application DB. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = {"medflow.appointment.expiration.enabled=false", "medflow.demo.accounts.enabled=false"})
class DrugCatalogMySqlIT {
    private static final String DATABASE_URL = isolatedUrl();
    @Autowired DrugService service;
    @Autowired DrugMapper mapper;
    @Autowired JdbcTemplate jdbc;
    String prefix;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout = 8");
        registry.add("medflow.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("medflow_confirmation_test");
        prefix = "TD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @AfterEach void cleanup() {
        if (prefix != null) jdbc.update("DELETE FROM med_drug WHERE code LIKE ?", prefix + "%");
    }

    @Test void enabledCatalogReflectsDisableAndReenableAndKeepsExactDecimalPrice() {
        Drug created = create("A", "12.30");
        assertThat(created.version()).isZero();
        assertThat(mapper.findById(created.id()).unitPrice()).isEqualTo(new BigDecimal("12.30"));
        assertThat(service.doctorPage(prefix, 1, 20).total()).isEqualTo(1);

        Drug disabled = service.update(created.id(), update("Disabled", "12.30", DrugStatus.DISABLED, 0));
        assertThat(disabled.code()).isEqualTo(created.code());
        assertThat(disabled.version()).isEqualTo(1);
        assertThat(service.doctorPage(prefix, 1, 20).items()).isEmpty();
        assertThat(service.adminPage(prefix, DrugStatus.DISABLED, 1, 20).total()).isEqualTo(1);

        service.update(created.id(), update("Enabled", "12.30", DrugStatus.ENABLED, 1));
        assertThat(service.doctorPage(prefix, 1, 20).items()).singleElement()
                .satisfies(drug -> assertThat(drug.version()).isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_drug WHERE id=?", Long.class, created.id())).isEqualTo(1);
    }

    @Test void concurrentEditorsAtSameVersionProduceOneWinnerAndOneConflict() throws Exception {
        Drug initial = create("A", "1.00");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Object> first = pool.submit(() -> {
                gate.await();
                return outcome(() -> service.update(initial.id(), update("First", "1.23", DrugStatus.ENABLED, 0)));
            });
            Future<Object> second = pool.submit(() -> {
                gate.await();
                return outcome(() -> service.update(initial.id(), update("Second", "9.87", DrugStatus.DISABLED, 0)));
            });
            gate.countDown();
            List<Object> results = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Drug.class::isInstance)).hasSize(1);
            assertThat(results).contains(ErrorCode.DRUG_VERSION_CONFLICT);
            Drug winner = (Drug) results.stream().filter(Drug.class::isInstance).findFirst().orElseThrow();
            assertThat(mapper.findById(initial.id())).isEqualTo(winner);
            assertThat(winner.version()).isEqualTo(1);
        }
    }

    @Test void duplicateAndInvalidPricesLeaveNoPartialRowsAndDatabaseAlsoRejectsZero() {
        Drug original = create("A", "0.01");
        assertThatThrownBy(() -> create("A", "12.00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DRUG_CODE_ALREADY_EXISTS));
        for (String amount : List.of("0", "-1", "0.001", "9999999.99")) {
            assertThatThrownBy(() -> create("INVALID", amount)).isInstanceOf(BusinessException.class);
        }
        assertThat(service.adminPage(prefix, null, 1, 20).total()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE med_drug SET unit_price=0 WHERE id=?", original.id()))
                .isInstanceOfSatisfying(DataAccessException.class, failure ->
                        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, sqlFailure -> {
                            assertThat(sqlFailure.getErrorCode()).isEqualTo(3819);
                            assertThat(sqlFailure.getMessage()).contains("chk_drug_price");
                        }));
        assertThat(mapper.findById(original.id()).unitPrice()).isEqualTo(new BigDecimal("0.01"));
    }

    @Test void literalKeywordAndPaginationReturnStableNonoverlappingRows() {
        Drug a = create("_A", "1.00");
        create("XA", "2.00");
        create("_C", "3.00");
        DrugPageResponse literal = service.adminPage(a.code(), null, 1, 20);
        assertThat(literal.total()).isEqualTo(1);
        assertThat(literal.items()).extracting(Drug::id).containsExactly(a.id());

        DrugPageResponse first = service.adminPage(prefix, null, 1, 2);
        DrugPageResponse second = service.adminPage(prefix, null, 2, 2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        Set<Long> ids = new HashSet<>();
        first.items().forEach(drug -> ids.add(drug.id()));
        second.items().forEach(drug -> ids.add(drug.id()));
        assertThat(ids).hasSize(3);
        assertThat(service.adminPage(prefix, null, 99, 2).items()).isEmpty();
    }

    private Drug create(String suffix, String amount) {
        return service.create(new CreateDrugRequest(prefix + suffix, prefix + " " + suffix,
                "Simulation specification 10mg", "box", new BigDecimal(amount)));
    }
    private UpdateDrugRequest update(String name, String price, DrugStatus status, long version) {
        return new UpdateDrugRequest(prefix + " " + name, "Simulation specification 10mg", "box", new BigDecimal(price), status, version);
    }
    private Object outcome(Supplier<Drug> action) {
        try { return action.get(); }
        catch (BusinessException failure) { return failure.getErrorCode(); }
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
