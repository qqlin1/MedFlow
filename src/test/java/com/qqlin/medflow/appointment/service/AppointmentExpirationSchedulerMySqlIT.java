package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.MedFlowApplication;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual scheduled callback, including application-context
 * shutdown and restart against the same disposable database. This verifies
 * Spring application restart compensation, not a separate JVM process restart.
 * No test calls the scheduler or expiration worker directly.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfEnvironmentVariable(named = "MEDFLOW_CONFIRMATION_TEST_URL", matches = ".+")
class AppointmentExpirationSchedulerMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s]*)?$"
    );
    private static final Duration SCHEDULER_WAIT = Duration.ofSeconds(10);

    @Test
    void scheduledCallbackExpiresOnceAndRecoversOverdueRowsAfterContextRestart()
            throws InterruptedException {
        String isolatedUrl = requireIsolatedDatabaseUrl();
        // This connection source remains usable after the application pool closes.
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                isolatedUrl, "root", ""
        ));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(TEST_SCHEMA);
        // An existing migrated schema may contain rows from a failed earlier
        // test. Remove them before enabling any timer; a fresh schema has none.
        if (requiredLong(jdbc, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'med_appointment'
                """) > 0) {
            cleanMutableTestData(jdbc);
        }

        ConfigurableApplicationContext runningContext = null;
        boolean fixturePrepared = false;
        try {
            runningContext = startApplication(isolatedUrl);
            cleanMutableTestData(jdbc);
            fixturePrepared = true;
            Fixture fixture = createFixture(jdbc);
            AppointmentService service = runningContext.getBean(AppointmentService.class);
            AppointmentCreationResult first = service.create(
                    fixture.ownerUserId(), new CreateAppointmentCommand(
                            fixture.firstPatientId(), fixture.slotId(),
                            "scheduler-first-" + fixture.suffix()
                    )
            );
            AppointmentCreationResult second = service.create(
                    fixture.ownerUserId(), new CreateAppointmentCommand(
                            fixture.secondPatientId(), fixture.slotId(),
                            "scheduler-second-" + fixture.suffix()
                    )
            );
            assertThat(first.status()).isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
            assertThat(second.status()).isEqualTo(AppointmentStatus.PENDING_CONFIRMATION);
            assertThat(remainingCapacity(jdbc, fixture.slotId())).isEqualTo(8);

            makeOverdue(jdbc, first.appointmentId());
            awaitCondition(() -> expired(jdbc, first.appointmentId())
                            && remainingCapacity(jdbc, fixture.slotId()) == 9,
                    "The real @Scheduled callback must expire the first appointment");
            Thread.sleep(400); // Several further ticks must not repeat the side effects.
            assertThat(remainingCapacity(jdbc, fixture.slotId())).isEqualTo(9);
            assertThat(expirationLogCount(jdbc, first.appointmentId())).isEqualTo(1);
            assertThat(expired(jdbc, second.appointmentId())).isFalse();

            runningContext.close();
            assertThat(runningContext.isActive()).isFalse();
            runningContext = null;

            makeOverdue(jdbc, second.appointmentId());
            Thread.sleep(400);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM med_appointment WHERE id = ?",
                    String.class, second.appointmentId()
            )).isEqualTo("PENDING_CONFIRMATION");
            assertThat(remainingCapacity(jdbc, fixture.slotId())).isEqualTo(9);
            assertThat(expirationLogCount(jdbc, second.appointmentId())).isZero();

            runningContext = startApplication(isolatedUrl);
            awaitCondition(() -> expired(jdbc, second.appointmentId())
                            && remainingCapacity(jdbc, fixture.slotId()) == 10,
                    "A restarted Spring application must recover the overdue appointment");
            Thread.sleep(400);
            assertThat(remainingCapacity(jdbc, fixture.slotId())).isEqualTo(10);
            assertThat(expirationLogCount(jdbc, first.appointmentId())).isEqualTo(1);
            assertThat(expirationLogCount(jdbc, second.appointmentId())).isEqualTo(1);
            assertThat(requiredLong(jdbc, """
                    SELECT COUNT(*) FROM med_appointment_status_log
                    WHERE appointment_id IN (?, ?)
                      AND event_type = 'EXPIRED'
                      AND from_status = 'PENDING_CONFIRMATION'
                      AND to_status = 'EXPIRED'
                      AND actor_user_id IS NULL
                    """, first.appointmentId(), second.appointmentId())).isEqualTo(2);
        } finally {
            // Stop the timer before deleting any data it might otherwise pick up.
            if (runningContext != null) {
                runningContext.close();
            }
            if (fixturePrepared) {
                cleanMutableTestData(jdbc);
            }
        }
    }

    private ConfigurableApplicationContext startApplication(String isolatedUrl) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                MedFlowApplication.class
        ).web(WebApplicationType.SERVLET).registerShutdownHook(false).run(
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
                "--medflow.appointment.expiration.enabled=true",
                "--medflow.appointment.expiration.poll-interval-ms=100",
                "--medflow.appointment.expiration.initial-delay-ms=100",
                "--medflow.appointment.expiration.batch-size=10"
        );
        try {
            assertThat(context.getEnvironment().getProperty(
                    "spring.datasource.url"
            )).isEqualTo(isolatedUrl);
            assertThat(context.getEnvironment().getProperty(
                    "medflow.appointment.expiration.poll-interval-ms"
            )).isEqualTo("100");
            assertThat(context.getEnvironment().getProperty(
                    "medflow.appointment.expiration.initial-delay-ms"
            )).isEqualTo("100");
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks()).isNotEmpty();
            return context;
        } catch (RuntimeException | AssertionError failure) {
            context.close();
            throw failure;
        }
    }

    private Fixture createFixture(JdbcTemplate jdbc) {
        long ownerUserId = requiredLong(jdbc,
                "SELECT id FROM sys_user WHERE username = 'patient.li'");
        long doctorUserId = requiredLong(jdbc,
                "SELECT id FROM sys_user WHERE username = 'doctor.wang'");
        long templateId = requiredLong(jdbc,
                "SELECT id FROM med_shift_template WHERE code = 'MORNING'");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long departmentId = insertAndGetId(jdbc, """
                INSERT INTO med_department (name, status)
                VALUES (?, 'ENABLED')
                """, "scheduler-department-" + suffix);
        long doctorId = insertAndGetId(jdbc, """
                INSERT INTO med_doctor (user_id, department_id, name, status)
                VALUES (?, ?, ?, 'ENABLED')
                """, doctorUserId, departmentId, "scheduler-doctor-" + suffix);
        long roomId = insertAndGetId(jdbc, """
                INSERT INTO med_clinic_room (department_id, name, status)
                VALUES (?, ?, 'ENABLED')
                """, departmentId, "scheduler-room-" + suffix);
        long scheduleId = insertAndGetId(jdbc, """
                INSERT INTO med_schedule (
                    doctor_id, clinic_room_id, shift_template_id, work_date,
                    start_time, end_time, slot_duration_minutes,
                    capacity_per_slot, status
                ) VALUES (?, ?, ?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00', '09:30:00', 30, 10, 'PUBLISHED')
                """, doctorId, roomId, templateId);
        long slotId = insertAndGetId(jdbc, """
                INSERT INTO med_slot (
                    schedule_id, work_date, start_time, end_time, booking_deadline,
                    total_capacity, remaining_capacity, status
                ) VALUES (?, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY),
                    '09:00:00', '09:30:00',
                    DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY), 10, 10, 'OPEN')
                """, scheduleId);
        long firstPatientId = insertPatient(jdbc, ownerUserId,
                "scheduler-patient-first-" + suffix);
        long secondPatientId = insertPatient(jdbc, ownerUserId,
                "scheduler-patient-second-" + suffix);
        return new Fixture(ownerUserId, firstPatientId, secondPatientId, slotId, suffix);
    }

    private long insertPatient(JdbcTemplate jdbc, long ownerUserId, String name) {
        return insertAndGetId(jdbc, """
                INSERT INTO med_patient (
                    owner_user_id, name, gender, birth_date, phone, relationship
                ) VALUES (?, ?, 'UNKNOWN', '2000-01-01', '13800138000', 'SELF')
                """, ownerUserId, name);
    }

    private void makeOverdue(JdbcTemplate jdbc, long appointmentId) {
        assertThat(jdbc.update("""
                UPDATE med_appointment
                SET confirm_deadline = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, appointmentId)).isEqualTo(1);
    }

    private boolean expired(JdbcTemplate jdbc, long appointmentId) {
        return "EXPIRED".equals(jdbc.queryForObject(
                "SELECT status FROM med_appointment WHERE id = ?",
                String.class, appointmentId
        ));
    }

    private long remainingCapacity(JdbcTemplate jdbc, long slotId) {
        return requiredLong(jdbc,
                "SELECT remaining_capacity FROM med_slot WHERE id = ?", slotId);
    }

    private long expirationLogCount(JdbcTemplate jdbc, long appointmentId) {
        return requiredLong(jdbc, """
                SELECT COUNT(*) FROM med_appointment_status_log
                WHERE appointment_id = ? AND event_type = 'EXPIRED'
                """, appointmentId);
    }

    private void awaitCondition(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SCHEDULER_WAIT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private long requiredLong(JdbcTemplate jdbc, String sql, Object... arguments) {
        Long result = jdbc.queryForObject(sql, Long.class, arguments);
        assertThat(result).isNotNull();
        return result;
    }

    private long insertAndGetId(JdbcTemplate jdbc, String sql, Object... arguments) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int affectedRows = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, keyHolder);
        assertThat(affectedRows).isEqualTo(1);
        Number generatedId = keyHolder.getKey();
        assertThat(generatedId).isNotNull();
        return generatedId.longValue();
    }

    private void cleanMutableTestData(JdbcTemplate jdbc) {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_confirmation_history");
        jdbc.update("DELETE FROM med_appointment_status_log");
        jdbc.update("DELETE FROM sys_idempotency_record");
        jdbc.update("DELETE FROM med_appointment");
        jdbc.update("DELETE FROM med_slot");
        jdbc.update("DELETE FROM med_schedule");
        jdbc.update("DELETE FROM med_patient");
        jdbc.update("DELETE FROM med_doctor");
        jdbc.update("DELETE FROM med_clinic_room");
        jdbc.update("DELETE FROM med_department");
    }

    private String requireIsolatedDatabaseUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher matcher = url == null ? null : ISOLATED_URL_PATTERN.matcher(url);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must point only to "
                            + "jdbc:mysql://127.0.0.1:<random-port>/" + TEST_SCHEMA
            );
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port <= 1024 || port > 65535 || port == 3306) {
            throw new IllegalStateException(
                    "MEDFLOW_CONFIRMATION_TEST_URL must use a non-default temporary MySQL port"
            );
        }
        return url;
    }

    private record Fixture(long ownerUserId, long firstPatientId,
                           long secondPatientId, long slotId, String suffix) {
    }
}
