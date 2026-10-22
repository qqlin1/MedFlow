package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.MedFlowApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Child JVM used by the strategy comparison. Only scan mode enables the
 * production scheduler; timer-register demonstrates one in-memory delayed task.
 * The parent owns and terminates only processes it creates from this harness.
 */
public final class ExpirationComparisonProcess {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s]*)?$"
    );
    private static final Set<String> MODES = Set.of("timer-register", "timer-empty", "scan");

    private ExpirationComparisonProcess() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 3 || !MODES.contains(args[0])) {
            throw new IllegalArgumentException(
                    "Arguments: <timer-register|timer-empty|scan> <isolated-jdbc-url> <appointment-id>"
            );
        }
        String mode = args[0];
        String isolatedUrl = requireIsolatedDatabaseUrl(args[1]);
        long appointmentId = Long.parseLong(args[2]);
        if (appointmentId <= 0) {
            throw new IllegalArgumentException("appointment-id must be positive");
        }

        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                MedFlowApplication.class
        ).web(WebApplicationType.SERVLET).registerShutdownHook(true).run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.datasource.url=" + isolatedUrl,
                "--spring.datasource.username=root",
                "--spring.datasource.password=",
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.datasource.hikari.connection-timeout=3000",
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
                "--medflow.security.jwt.secret=" + TEST_ONLY_JWT_SECRET,
                "--springdoc.api-docs.enabled=false",
                "--springdoc.swagger-ui.enabled=false",
                "--spring.task.scheduling.shutdown.await-termination=true",
                "--spring.task.scheduling.shutdown.await-termination-period=5s",
                "--medflow.appointment.expiration.enabled=" + "scan".equals(mode),
                "--medflow.appointment.expiration.poll-interval-ms=100",
                "--medflow.appointment.expiration.initial-delay-ms=100",
                "--medflow.appointment.expiration.batch-size=10"
        );
        boolean registerTimer = "timer-register".equals(mode);
        ScheduledThreadPoolExecutor timer = !"scan".equals(mode)
                ? new ScheduledThreadPoolExecutor(1) : null;
        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if (!TEST_SCHEMA.equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("The comparison process connected to an unexpected schema");
            }
            if (timer != null) {
                timer.setRemoveOnCancelPolicy(true);
                timer.prestartAllCoreThreads();
            }
            if (registerTimer) {
                AppointmentService service = context.getBean(AppointmentService.class);
                long delayMicros = Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT GREATEST(0, TIMESTAMPDIFF(
                            MICROSECOND, CURRENT_TIMESTAMP(3), confirm_deadline
                        )) FROM med_appointment WHERE id = ?
                        """, Long.class, appointmentId));
                timer.schedule(() -> service.expirePending(appointmentId), delayMicros, TimeUnit.MICROSECONDS);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (timer != null) {
                    timer.shutdownNow();
                }
                context.close();
            }, "expiration-comparison-shutdown"));
            System.out.println("COMPARISON_READY mode=" + mode
                    + " registered=" + (registerTimer ? 1 : 0)
                    + " timerPresent=" + (timer != null)
                    + " timerThreads=" + (timer == null ? 0 : timer.getPoolSize())
                    + " pid=" + ProcessHandle.current().pid());
            System.out.flush();
            new CountDownLatch(1).await();
        } finally {
            if (timer != null) {
                timer.shutdownNow();
            }
            context.close();
        }
    }

    private static String requireIsolatedDatabaseUrl(String url) {
        Matcher matcher = ISOLATED_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Comparison child must use jdbc:mysql://127.0.0.1:<temporary-port>/" + TEST_SCHEMA
            );
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException("Comparison child must use a non-default temporary MySQL port");
        }
        return url;
    }
}
