package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-transaction row-lock experiment against the runner's disposable MySQL.
 * Run with tools/test-confirmation.ps1 -TestSelection AppointmentDeadlockMySqlIT.
 * The SQL here is a controlled lock experiment, not an application service call.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class AppointmentDeadlockMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s]*)?$"
    );
    private static final String TEST_DATABASE_URL =
            requireIsolatedDatabaseUrl();
    private static final Path REPORT_PATH =
            Path.of("target", "deadlock-test", "latest.json");
    private static final long GATE_TIMEOUT_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 20;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, Object> report = new LinkedHashMap<>();
    private long scheduleId;
    private long slotId;
    private long appointmentId;
    private Snapshot initial;

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION innodb_lock_wait_timeout = 8");
        registry.add("medflow.security.jwt.secret",
                () -> TEST_ONLY_JWT_SECRET);
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @BeforeEach
    void createFixture() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(TEST_SCHEMA);

        long seed = UUID.randomUUID().getMostSignificantBits()
                & Long.MAX_VALUE;
        scheduleId = insertAndGetId("""
                INSERT INTO med_schedule (
                    doctor_id, clinic_room_id, work_date,
                    start_time, end_time, status
                ) VALUES (?, ?, DATE_ADD(CURRENT_DATE, INTERVAL 2 DAY),
                          '09:00:00', '09:30:00', 'PUBLISHED')
                """, seed | 1L, seed | 2L);
        slotId = insertAndGetId("""
                INSERT INTO med_slot (
                    schedule_id, work_date, start_time, end_time,
                    booking_deadline, total_capacity, remaining_capacity, status
                ) VALUES (?, DATE_ADD(CURRENT_DATE, INTERVAL 2 DAY),
                          '09:00:00', '09:30:00',
                          DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),
                          3, 2, 'OPEN')
                """, scheduleId);
        appointmentId = insertAndGetId("""
                INSERT INTO med_appointment (
                    patient_id, slot_id, status,
                    confirm_deadline, cancel_deadline
                ) VALUES (?, ?, 'PENDING_CONFIRMATION',
                          DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),
                          DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY))
                """, UUID.randomUUID().getLeastSignificantBits()
                        & Long.MAX_VALUE | 1L, slotId);
        initial = readSnapshot();
    }

    @AfterEach
    void removeFixture() {
        if (appointmentId != 0) {
            jdbcTemplate.update("DELETE FROM med_appointment WHERE id = ?",
                    appointmentId);
        }
        if (slotId != 0) {
            jdbcTemplate.update("DELETE FROM med_slot WHERE id = ?", slotId);
        }
        if (scheduleId != 0) {
            jdbcTemplate.update("DELETE FROM med_schedule WHERE id = ?",
                    scheduleId);
        }
    }

    @Test
    @Order(1)
    void inverseLockOrderDeadlocksAndRollsBackOneWholeTransaction()
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch bothFirstLocksHeld = new CountDownLatch(2);
        CountDownLatch startSecondLocks = new CountDownLatch(1);
        boolean gateReady;
        boolean workersTerminated;
        List<TxOutcome> outcomes;
        try {
            Future<TxOutcome> slotFirst = workers.submit(() -> runTransaction(
                    "slot-first", true, 1, bothFirstLocksHeld,
                    startSecondLocks));
            Future<TxOutcome> appointmentFirst = workers.submit(
                    () -> runTransaction("appointment-first", false, 2,
                            bothFirstLocksHeld, startSecondLocks));
            gateReady = bothFirstLocksHeld.await(
                    GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            startSecondLocks.countDown();
            outcomes = List.of(slotFirst.get(FUTURE_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS),
                    appointmentFirst.get(FUTURE_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS));
        } finally {
            startSecondLocks.countDown();
            workers.shutdownNow();
            workersTerminated = workers.awaitTermination(FUTURE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }

        // Capture this immediately: the server keeps only the latest deadlock.
        String deadlock = latestDeadlockExcerpt();
        Snapshot after = readSnapshot();
        Map<String, Object> inverse = new LinkedHashMap<>();
        inverse.put("lockOrders", List.of(
                "slot-first: med_slot -> med_appointment",
                "appointment-first: med_appointment -> med_slot"));
        inverse.put("bothFirstLocksHeldBeforeSecondRequests", gateReady);
        inverse.put("workersTerminated", workersTerminated);
        inverse.put("transactions", outcomes);
        inverse.put("initial", initial);
        inverse.put("after", after);
        inverse.put("innoDbLatestDetectedDeadlock", deadlock);
        report.put("inverseOrder", inverse);
        writeReport();

        assertThat(gateReady)
                .as("both transactions held their first row lock")
                .isTrue();
        assertThat(workersTerminated).isTrue();
        assertThat(outcomes.stream().filter(TxOutcome::committed).count())
                .isEqualTo(1);
        List<TxOutcome> victims = outcomes.stream()
                .filter(outcome -> !outcome.committed()).toList();
        assertThat(victims).hasSize(1);
        assertThat(victims.getFirst().mysqlErrorCode()).isEqualTo(1213);
        assertThat(victims.getFirst().sqlState()).isEqualTo("40001");
        assertThat(deadlock)
                .contains("med_slot", "med_appointment",
                        "*** (1) TRANSACTION", "*** (2) TRANSACTION",
                        "HOLDS THE LOCK(S)",
                        "WAITING FOR THIS LOCK TO BE GRANTED",
                        "WE ROLL BACK TRANSACTION");
        assertThat(deadlock.split("WAITING FOR THIS LOCK TO BE GRANTED", -1))
                .hasSize(3);

        TxOutcome survivor = outcomes.stream().filter(TxOutcome::committed)
                .findFirst().orElseThrow();
        int expectedMinutes = survivor.name().equals("slot-first") ? 1 : 2;
        assertThat(after.slotDeadline())
                .isEqualTo(initial.slotDeadline().plusMinutes(expectedMinutes));
        assertThat(after.appointmentDeadline())
                .isEqualTo(initial.appointmentDeadline()
                        .plusMinutes(expectedMinutes));
        assertThat(after.remainingCapacity())
                .isEqualTo(initial.remainingCapacity());
        assertThat(after.appointmentStatus())
                .isEqualTo(initial.appointmentStatus());
    }

    @Test
    @Order(2)
    void fixedSlotThenAppointmentOrderCompletesAfterOrdinaryLockWait()
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstSlotHeld = new CountDownLatch(1);
        CountDownLatch secondAttemptingSlot = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        boolean observedLockWait;
        boolean workersTerminated;
        List<TxOutcome> outcomes;
        try {
            Future<TxOutcome> first = workers.submit(() -> runOrderedFirst(
                    firstSlotHeld, releaseFirst));
            Future<TxOutcome> second = workers.submit(() -> runOrderedSecond(
                    firstSlotHeld, secondAttemptingSlot));
            boolean firstReady = firstSlotHeld.await(GATE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            boolean secondReady = secondAttemptingSlot.await(
                    GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            observedLockWait = firstReady && secondReady
                    && waitForSlotLockWait(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            outcomes = List.of(first.get(FUTURE_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS),
                    second.get(FUTURE_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            workersTerminated = workers.awaitTermination(FUTURE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }

        Snapshot after = readSnapshot();
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("lockOrder", "both: med_slot -> med_appointment");
        fixed.put("ordinarySlotLockWaitObservedInPerformanceSchema",
                observedLockWait);
        fixed.put("workersTerminated", workersTerminated);
        fixed.put("transactions", outcomes);
        fixed.put("initial", initial);
        fixed.put("after", after);
        report.put("fixedOrder", fixed);
        writeReport();

        assertThat(observedLockWait).isTrue();
        assertThat(workersTerminated).isTrue();
        assertThat(outcomes).allMatch(TxOutcome::committed);
        assertThat(after.slotDeadline())
                .isEqualTo(initial.slotDeadline().plusMinutes(3));
        assertThat(after.appointmentDeadline())
                .isEqualTo(initial.appointmentDeadline().plusMinutes(3));
        assertThat(after.remainingCapacity())
                .isEqualTo(initial.remainingCapacity());
        assertThat(after.appointmentStatus())
                .isEqualTo(initial.appointmentStatus());
    }

    private TxOutcome runOrderedFirst(CountDownLatch firstSlotHeld,
                                      CountDownLatch releaseFirst) {
        return runTransaction("fixed-first", true, 1,
                firstSlotHeld, releaseFirst);
    }

    private TxOutcome runOrderedSecond(CountDownLatch firstSlotHeld,
                                       CountDownLatch secondAttemptingSlot) {
        Connection connection = null;
        try {
            if (!firstSlotHeld.await(GATE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                return TxOutcome.failed("fixed-second", "first slot gate timed out");
            }
            connection = openTransaction();
            secondAttemptingSlot.countDown();
            lockSlot(connection);
            advanceSlotDeadline(connection, 2);
            lockAppointment(connection);
            advanceAppointmentDeadline(connection, 2);
            connection.commit();
            return TxOutcome.committed("fixed-second");
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            return TxOutcome.sqlFailure("fixed-second", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rollbackQuietly(connection);
            return TxOutcome.failed("fixed-second", "interrupted");
        } finally {
            closeQuietly(connection);
        }
    }

    private TxOutcome runTransaction(String name, boolean slotFirst,
                                     int minutes, CountDownLatch firstLockHeld,
                                     CountDownLatch releaseSecond) {
        Connection connection = null;
        try {
            connection = openTransaction();
            if (slotFirst) {
                lockSlot(connection);
                advanceSlotDeadline(connection, minutes);
            } else {
                lockAppointment(connection);
                advanceAppointmentDeadline(connection, minutes);
            }
            firstLockHeld.countDown();
            if (!releaseSecond.await(GATE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                rollbackQuietly(connection);
                return TxOutcome.failed(name, "second lock gate timed out");
            }
            if (slotFirst) {
                lockAppointment(connection);
                advanceAppointmentDeadline(connection, minutes);
            } else {
                lockSlot(connection);
                advanceSlotDeadline(connection, minutes);
            }
            connection.commit();
            return TxOutcome.committed(name);
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            return TxOutcome.sqlFailure(name, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rollbackQuietly(connection);
            return TxOutcome.failed(name, "interrupted");
        } finally {
            closeQuietly(connection);
        }
    }

    private Connection openTransaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = 8");
        }
        return connection;
    }

    private void lockSlot(Connection connection) throws SQLException {
        lockRow(connection, "SELECT id FROM med_slot WHERE id = ? FOR UPDATE",
                slotId);
    }

    private void lockAppointment(Connection connection) throws SQLException {
        lockRow(connection,
                "SELECT id FROM med_appointment WHERE id = ? FOR UPDATE",
                appointmentId);
    }

    private static void lockRow(Connection connection, String sql, long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(12);
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("fixture row disappeared: " + id);
                }
            }
        }
    }

    private void advanceSlotDeadline(Connection connection, int minutes)
            throws SQLException {
        advanceDeadline(connection, """
                UPDATE med_slot
                SET booking_deadline = TIMESTAMPADD(MINUTE, ?, booking_deadline)
                WHERE id = ?
                """, minutes, slotId);
    }

    private void advanceAppointmentDeadline(Connection connection, int minutes)
            throws SQLException {
        advanceDeadline(connection, """
                UPDATE med_appointment
                SET confirm_deadline = TIMESTAMPADD(MINUTE, ?, confirm_deadline)
                WHERE id = ?
                """, minutes, appointmentId);
    }

    private static void advanceDeadline(Connection connection, String sql,
                                        int minutes, long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(12);
            statement.setInt(1, minutes);
            statement.setLong(2, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("fixture update missed row: " + id);
            }
        }
    }

    private boolean waitForSlotLockWait(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits wait_edge
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID =
                         wait_edge.REQUESTING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = ?
                      AND requested.OBJECT_NAME = 'med_slot'
                    """, Integer.class, TEST_SCHEMA);
            if (waits != null && waits > 0) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private String latestDeadlockExcerpt() {
        String status = jdbcTemplate.queryForObject(
                "SHOW ENGINE INNODB STATUS",
                (rows, index) -> rows.getString("Status"));
        if (status == null) {
            return "SHOW ENGINE INNODB STATUS returned no status text";
        }
        String normalized = status.replace("\r\n", "\n");
        int start = normalized.indexOf("LATEST DETECTED DEADLOCK");
        if (start < 0) {
            return "LATEST DETECTED DEADLOCK section missing";
        }
        int end = normalized.indexOf("\n------------\nTRANSACTIONS",
                start);
        return normalized.substring(start, end < 0 ? normalized.length() : end);
    }

    private Snapshot readSnapshot() {
        LocalDateTime slotDeadline = jdbcTemplate.queryForObject(
                "SELECT booking_deadline FROM med_slot WHERE id = ?",
                (rows, index) -> rows.getObject(1, LocalDateTime.class),
                slotId);
        LocalDateTime appointmentDeadline = jdbcTemplate.queryForObject(
                "SELECT confirm_deadline FROM med_appointment WHERE id = ?",
                (rows, index) -> rows.getObject(1, LocalDateTime.class),
                appointmentId);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_capacity FROM med_slot WHERE id = ?",
                Integer.class, slotId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM med_appointment WHERE id = ?",
                String.class, appointmentId);
        assertThat(slotDeadline).isNotNull();
        assertThat(appointmentDeadline).isNotNull();
        assertThat(remaining).isNotNull();
        assertThat(status).isNotNull();
        return new Snapshot(slotDeadline, appointmentDeadline,
                remaining, status);
    }

    private void writeReport() throws Exception {
        report.put("timestampUtc", Instant.now().toString());
        report.put("scope", "direct JDBC transactions on one disposable MySQL fixture");
        report.put("databaseSchema", TEST_SCHEMA);
        report.put("mysqlVersion", jdbcTemplate.queryForObject(
                "SELECT VERSION()", String.class));
        Files.createDirectories(REPORT_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_PATH.toFile(), report);
    }

    private long insertAndGetId(String sql, Object... arguments) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int affected = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, keyHolder);
        assertThat(affected).isEqualTo(1);
        Number id = keyHolder.getKey();
        assertThat(id).isNotNull();
        return id.longValue();
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve the original SQL failure in the outcome.
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The transaction has already committed or rolled back.
            }
        }
    }

    private static String requireIsolatedDatabaseUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher matcher = url == null ? null
                : ISOLATED_URL_PATTERN.matcher(url);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must point only to "
                            + "jdbc:mysql://127.0.0.1:<random-port>/"
                            + TEST_SCHEMA);
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must use a "
                            + "non-default temporary MySQL port");
        }
        return url;
    }

    private record Snapshot(LocalDateTime slotDeadline,
                            LocalDateTime appointmentDeadline,
                            int remainingCapacity,
                            String appointmentStatus) {
    }

    private record TxOutcome(String name, boolean committed,
                             Integer mysqlErrorCode, String sqlState,
                             String detail) {
        private static TxOutcome committed(String name) {
            return new TxOutcome(name, true, null, null, null);
        }

        private static TxOutcome sqlFailure(String name,
                                            SQLException exception) {
            return new TxOutcome(name, false, exception.getErrorCode(),
                    exception.getSQLState(), exception.getMessage());
        }

        private static TxOutcome failed(String name, String detail) {
            return new TxOutcome(name, false, null, null, detail);
        }
    }
}
