package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationBatch;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fair comparison of a once-registered in-memory timer and the production scan
 * worker. Both execute the same proxied AppointmentService transaction against
 * disposable MySQL. No Quartz or RabbitMQ implementation is implied here.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfEnvironmentVariable(named = "MEDFLOW_CONFIRMATION_TEST_URL", matches = ".+")
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class AppointmentExpirationStrategyMySqlIT {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String TRIGGER = "test_fail_expiration_comparison_history";
    private static final int POLL_MS = 100;
    private static final Duration WAIT = Duration.ofSeconds(8);
    private static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();

    @Autowired
    private AppointmentService service;
    @Autowired
    private AppointmentRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    private ExpirationComparisonFixture fixture;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AppointmentExpirationStrategyMySqlIT::isolatedUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout = 8");
        registry.add("medflow.security.jwt.secret", () -> SECRET);
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @BeforeEach
    void seed() {
        fixture = new ExpirationComparisonFixture(jdbc, service);
        resetFixture();
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void bothStrategiesCloseNormallyAndRecordDeadlineToUpdateDelay() throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        Map<Strategy, List<Double>> lagsByStrategy = new LinkedHashMap<>();
        Map<Strategy, List<Map<String, Integer>>> countersByStrategy = new LinkedHashMap<>();
        for (Strategy strategy : Strategy.values()) {
            lagsByStrategy.put(strategy, new ArrayList<>());
            countersByStrategy.put(strategy, new ArrayList<>());
        }
        // Alternate the strategies, discard two warm-up rounds, then measure five.
        for (int round = 0; round < 7; round++) {
            for (Strategy strategy : Strategy.values()) {
                resetFixture();
                long id = fixture.createPending();
                fixture.setDeadlineAfterMillis(id, 350);
                try (Harness harness = new Harness(strategy)) {
                    harness.start(List.of(id));
                    await(() -> fixture.status(id).equals("EXPIRED") && harness.expired.get() == 1, WAIT,
                            strategy + " must eventually expire the appointment");
                    assertThat(fixture.capacity()).isEqualTo(10);
                    assertThat(fixture.expiredLogCount(id)).isEqualTo(1);
                    Double lag = jdbc.queryForObject("""
                            SELECT TIMESTAMPDIFF(MICROSECOND, confirm_deadline, updated_at) / 1000.0
                            FROM med_appointment WHERE id = ?
                            """, Double.class, id);
                    assertThat(lag).isNotNull().isGreaterThanOrEqualTo(0);
                    if (round >= 2) {
                        lagsByStrategy.get(strategy).add(lag);
                        countersByStrategy.get(strategy).add(harness.counters());
                    }
                }
            }
        }
        for (Strategy strategy : Strategy.values()) {
            List<Double> lags = lagsByStrategy.get(strategy);
            List<Double> ordered = lags.stream().sorted().toList();
            results.put(strategy.name(), Map.of("rounds", 5, "deadlineToStatusUpdateMs", lags,
                    "medianMs", ordered.get(2), "counters", countersByStrategy.get(strategy)));
        }
        EVIDENCE.put("normal", results);
    }

    @Test
    void defaultFiveSecondIntervalHasPollingDelayWhenDeadlineFallsAfterAnEmptyPoll() throws Exception {
        long id = fixture.createPending();
        fixture.setDeadlineAfterMillis(id, 60_000);
        try (Harness scan = new Harness(Strategy.SCAN, 5000)) {
            scan.start(List.of());
            await(() -> scan.completedPolls.get() >= 1, WAIT, "first empty poll must finish");
            assertThat(scan.attempts.get()).isZero();
            // Make the deadline fall shortly after a known empty poll, without
            // waiting through the business's real fifteen-minute window.
            fixture.setDeadlineAfterMillis(id, 350);
            await(() -> fixture.status(id).equals("EXPIRED") && scan.expired.get() == 1,
                    WAIT, "next default-interval poll must close the appointment");
            Double lag = jdbc.queryForObject("""
                    SELECT TIMESTAMPDIFF(MICROSECOND, confirm_deadline, updated_at) / 1000.0
                    FROM med_appointment WHERE id = ?
                    """, Double.class, id);
            assertThat(fixture.capacity()).isEqualTo(10);
            assertThat(fixture.expiredLogCount(id)).isEqualTo(1);
            EVIDENCE.put("defaultIntervalPhase", Map.of("pollMs", 5000, "samples", 1,
                    "deadlineSetAfterKnownEmptyPollMs", 350, "deadlineToStatusUpdateMs", lag,
                    "counters", scan.counters(), "scope", "one controlled phase, not a latency distribution or production bound"));
        }
    }

    @Test
    void bothStrategiesLeaveConfirmedAndCancelledAppointmentsUntouched() throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        for (Strategy strategy : Strategy.values()) {
            resetFixture();
            long booked = fixture.createPending();
            long cancelled = fixture.createPending();
            long ownerId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='patient.li'", Long.class);
            service.confirm(ownerId, booked);
            service.cancel(ownerId, cancelled);
            fixture.setDeadlineAfterMillis(booked, 150);
            fixture.setDeadlineAfterMillis(cancelled, 150);
            try (Harness harness = new Harness(strategy)) {
                harness.start(List.of(booked, cancelled));
                if (strategy == Strategy.TIMER) {
                    for (ScheduledFuture<?> future : harness.futures) {
                        future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    }
                } else {
                    await(() -> harness.scanQueries.get() >= 3, WAIT, "scanner must execute several polls");
                }
                assertThat(fixture.status(booked)).isEqualTo("BOOKED");
                assertThat(fixture.status(cancelled)).isEqualTo("CANCELLED");
                assertThat(fixture.capacity()).isEqualTo(9);
                assertThat(fixture.expiredLogCount(booked) + fixture.expiredLogCount(cancelled)).isZero();
                if (strategy == Strategy.TIMER) {
                    assertThat(harness.skipped.get()).isEqualTo(2);
                } else {
                    assertThat(harness.attempts.get()).isZero();
                }
                results.put(strategy.name(), harness.counters());
            }
        }
        EVIDENCE.put("alreadyChanged", results);
    }

    @Test
    void scannerRetriesAfterHistoryFaultButOneShotTimerNeedsAnExplicitRetry() throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        for (Strategy strategy : Strategy.values()) {
            resetFixture();
            long id = fixture.createPending();
            fixture.setDue(id);
            jdbc.execute("""
                    CREATE TRIGGER test_fail_expiration_comparison_history
                    BEFORE INSERT ON med_appointment_status_log
                    FOR EACH ROW BEGIN
                        IF NEW.event_type = 'EXPIRED' THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'comparison-only history failure';
                        END IF;
                    END
                    """);
            try (Harness harness = new Harness(strategy)) {
                harness.start(List.of(id));
                await(() -> harness.failed.get() >= 1, WAIT, "injected history failure must occur");
                if (strategy == Strategy.TIMER) {
                    assertThatThrownBy(() -> harness.futures.getFirst().get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                            .isInstanceOf(ExecutionException.class);
                }
                assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
                assertThat(fixture.capacity()).isEqualTo(9);
                assertThat(fixture.expiredLogCount(id)).isZero();
                jdbc.execute("DROP TRIGGER " + TRIGGER);
                if (strategy == Strategy.SCAN) {
                    await(() -> fixture.status(id).equals("EXPIRED"), WAIT, "scanner must retry after fault removal");
                    assertThat(fixture.capacity()).isEqualTo(10);
                    assertThat(fixture.expiredLogCount(id)).isEqualTo(1);
                } else {
                    Thread.sleep(350);
                    assertThat(harness.attempts.get()).isEqualTo(1);
                    assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
                    assertThat(fixture.capacity()).isEqualTo(9);
                }
                results.put(strategy.name(), Map.of("statusAfterFaultRemoval", fixture.status(id),
                        "capacity", fixture.capacity(), "counters", harness.counters()));
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
            }
        }
        EVIDENCE.put("callbackFailure", results);
    }

    @Test
    void scannerFindsCommittedAppointmentWhenTimerRegistrationWasOmitted() throws Exception {
        long id = fixture.createPending();
        fixture.setDue(id);
        // Controlled fault window: the business transaction committed, then the
        // request path did not reach timer registration. No timer is registered.
        try (Harness timer = new Harness(Strategy.TIMER)) {
            timer.start(List.of());
            Thread.sleep(350);
            assertThat(timer.attempts.get()).isZero();
            assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
            assertThat(fixture.capacity()).isEqualTo(9);
        }
        try (Harness scan = new Harness(Strategy.SCAN)) {
            scan.start(List.of());
            await(() -> fixture.status(id).equals("EXPIRED"), WAIT, "scan must recover an unregistered appointment");
            assertThat(fixture.capacity()).isEqualTo(10);
            assertThat(fixture.expiredLogCount(id)).isEqualTo(1);
            EVIDENCE.put("registrationGap", Map.of("faultType", "controlled omission after committed create",
                    "timerWithoutRegistration", "PENDING_CONFIRMATION", "scanAfterward", fixture.status(id),
                    "scanCounters", scan.counters()));
        }
    }

    @Test
    void separateJvmRestartLosesInMemoryTaskWhileProductionScannerRecoversDatabaseRow() throws Exception {
        long id = fixture.createPending();
        fixture.setDeadlineAfterMillis(id, 60_000);
        Path logs = Path.of("target", "expiration-strategy-process", UUID.randomUUID().toString());
        Files.createDirectories(logs);
        List<Long> pids = new ArrayList<>();
        try (ChildApplication first = startChild("timer-register", id, logs.resolve("timer-before-stop.log"))) {
            pids.add(first.process.pid());
            assertThat(first.output()).contains("registered=1", "timerPresent=true", "timerThreads=1");
            assertThat(fixture.databaseMillisUntilDeadline(id)).isGreaterThan(0);
            assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
            // Terminate our own child JVM before the registered callback fires.
        }
        fixture.setDue(id); // Advance the database deadline to keep the test short.
        try (ChildApplication restarted = startChild("timer-empty", id, logs.resolve("timer-after-restart.log"))) {
            pids.add(restarted.process.pid());
            assertThat(restarted.output()).contains("registered=0", "timerPresent=true", "timerThreads=1");
            Thread.sleep(500);
            assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
            assertThat(fixture.capacity()).isEqualTo(9);
            assertThat(fixture.expiredLogCount(id)).isZero();
        }
        // Run an independent scan -> stop -> scan trajectory, so both strategies
        // have an original and a restarted JVM rather than switching strategies
        // on the timer's old row and calling that a scanner restart.
        resetFixture();
        long scanId = fixture.createPending();
        fixture.setDeadlineAfterMillis(scanId, 60_000);
        try (ChildApplication originalScan = startChild("scan", scanId, logs.resolve("scan-before-stop.log"))) {
            pids.add(originalScan.process.pid());
            assertThat(fixture.databaseMillisUntilDeadline(scanId)).isGreaterThan(0);
            assertThat(fixture.status(scanId)).isEqualTo("PENDING_CONFIRMATION");
        }
        fixture.setDue(scanId);
        try (ChildApplication scanning = startChild("scan", scanId, logs.resolve("scan-after-restart.log"))) {
            pids.add(scanning.process.pid());
            await(() -> fixture.status(scanId).equals("EXPIRED"), WAIT, "new scanner JVM must recover persisted work");
            assertThat(fixture.capacity()).isEqualTo(10);
            assertThat(fixture.expiredLogCount(scanId)).isEqualTo(1);
        }
        EVIDENCE.put("jvmRestart", Map.of("childPids", pids, "logs", logs.toAbsolutePath().toString(),
                "timerRegisteredBeforeTermination", 1, "timerRegisteredAfterRestart", 0,
                "timerStateAfterRestart", "PENDING_CONFIRMATION", "scannerState", fixture.status(scanId),
                "capacity", fixture.capacity(), "expiredHistoryRows", fixture.expiredLogCount(scanId),
                "faultType", "two separate original/restarted JVM pairs; destroyForcibly before deadline, manually advance DB deadline while stopped; restarted timer executor is started but has no restored task"));
    }

    @Test
    void deadlineStillRejectsConfirmationBeforeBackgroundClosureRuns() {
        long id = fixture.createPending();
        fixture.setDue(id);
        long ownerId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='patient.li'", Long.class);
        assertThat(fixture.status(id)).isEqualTo("PENDING_CONFIRMATION");
        assertThatThrownBy(() -> service.confirm(ownerId, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_CONFIRMATION_EXPIRED));
        assertThat(fixture.capacity()).isEqualTo(9);
        EVIDENCE.put("deadlineEnforcement", Map.of("backgroundClosureStarted", false,
                "confirmationResult", "APPOINTMENT_CONFIRMATION_EXPIRED", "status", fixture.status(id)));
    }

    @AfterAll
    static void saveEvidence() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runDate", "2026-10-02");
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("scope", "test harness once-per-appointment in-memory timer vs actual production Worker; same Spring-proxied transaction and disposable MySQL");
        result.put("timing", "2 warmup + 5 measured alternating rounds per strategy; 350ms deadlines, scan fixed delay 100ms, batch100, scheduler threads2; deadline-to-status UPDATE timestamp from MySQL, not HTTP/commit latency; default5s interval has a separate one-sample controlled phase");
        result.put("expectedScenarios", 7);
        result.put("recordedScenarios", EVIDENCE.size());
        result.put("scenarios", EVIDENCE);
        Path path = Path.of(".codex", "evidence", "expiration-strategy-comparison-2026-10-02.json");
        Files.createDirectories(path.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), result);
    }

    private void resetFixture() {
        fixture.clean();
        fixture.seed();
    }

    private enum Strategy { TIMER, SCAN }

    private final class Harness implements AutoCloseable {
        private final Strategy strategy;
        private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        private final List<ScheduledFuture<?>> futures = new ArrayList<>();
        private final AtomicInteger scanQueries = new AtomicInteger();
        private final AtomicInteger deadlineQueries = new AtomicInteger();
        private final AtomicInteger completedPolls = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger expired = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final int pollMs;

        private Harness(Strategy strategy) {
            this(strategy, POLL_MS);
        }

        private Harness(Strategy strategy, int pollMs) {
            this.strategy = strategy;
            this.pollMs = pollMs;
            executor.setRemoveOnCancelPolicy(true);
        }

        private void start(List<Long> ids) {
            if (strategy == Strategy.TIMER) {
                for (long id : ids) {
                    deadlineQueries.incrementAndGet();
                    long delay = Math.max(0, fixture.databaseMillisUntilDeadline(id));
                    futures.add(executor.schedule(() -> {
                        attempts.incrementAndGet();
                        try {
                            if (service.expirePending(id)) {
                                expired.incrementAndGet();
                            } else {
                                skipped.incrementAndGet();
                            }
                        } catch (RuntimeException exception) {
                            failed.incrementAndGet();
                            throw exception;
                        }
                    }, delay, TimeUnit.MILLISECONDS));
                }
            } else {
                AppointmentRepository countingRepository = new AppointmentRepository(null) {
                    @Override
                    public List<AppointmentExpirationCandidate> findDuePendingAfter(
                            AppointmentExpirationCandidate cursor, int limit) {
                        scanQueries.incrementAndGet();
                        return repository.findDuePendingAfter(cursor, limit);
                    }
                };
                AppointmentExpirationWorker worker = new AppointmentExpirationWorker(countingRepository, service, 100);
                futures.add(executor.scheduleWithFixedDelay(() -> {
                    AppointmentExpirationBatch batch = worker.runBatch();
                    attempts.addAndGet(batch.scanned());
                    expired.addAndGet(batch.expired());
                    skipped.addAndGet(batch.skipped());
                    failed.addAndGet(batch.failed());
                    completedPolls.incrementAndGet();
                }, 0, pollMs, TimeUnit.MILLISECONDS));
            }
        }

        private Map<String, Integer> counters() {
            return Map.of("candidateSelects", scanQueries.get(), "registrationDeadlineSelects", deadlineQueries.get(),
                    "completedPolls", completedPolls.get(), "expirationCalls", attempts.get(),
                    "expired", expired.get(), "skipped", skipped.get(), "failed", failed.get());
        }

        @Override
        public void close() throws InterruptedException {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ChildApplication startChild(String mode, long id, Path log) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
                ExpirationComparisonProcess.class.getName(), mode, isolatedUrl(), Long.toString(id))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        ChildApplication child = new ChildApplication(process, log);
        try {
            await(() -> {
                if (!process.isAlive()) {
                    throw new AssertionError("Child JVM exited before READY: " + child.output());
                }
                return child.output().contains("COMPARISON_READY mode=" + mode);
            }, Duration.ofSeconds(30), "child JVM must initialize: " + mode);
            return child;
        } catch (Exception | AssertionError failure) {
            child.close();
            throw failure;
        }
    }

    private record ChildApplication(Process process, Path log) implements AutoCloseable {
        private String output() {
            try {
                return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void close() throws InterruptedException {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("our child JVM must terminate").isTrue();
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout, String message) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    private static String isolatedUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Pattern pattern = Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$");
        Matcher matcher = url == null ? null : pattern.matcher(url);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException("An isolated loopback medflow_confirmation_test URL is required");
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port == 3306 || port > 65535) {
            throw new IllegalStateException("Only a disposable MySQL instance at a non-default temporary port is allowed");
        }
        return url;
    }
}
