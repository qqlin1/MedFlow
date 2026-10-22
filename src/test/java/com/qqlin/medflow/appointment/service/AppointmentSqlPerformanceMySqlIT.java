package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentPage;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducible query-plan experiment. Run only with
 * tools/test-confirmation.ps1 -TestSelection AppointmentSqlPerformanceMySqlIT.
 * The disposable server is started and removed by that runner.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = "medflow.appointment.expiration.enabled=false")
class AppointmentSqlPerformanceMySqlIT {

    private static final String TEST_SCHEMA = "medflow_confirmation_test";
    private static final int APPOINTMENTS = 100_000;
    private static final int SCHEDULES = 365;
    private static final int SLOTS_PER_SCHEDULE = 8;
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 5;
    private static final String CANDIDATE_INDEX = "idx_appointment_patient_created_id";
    private static final String TEST_ONLY_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Pattern ISOLATED_URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/"
                    + TEST_SCHEMA + "(?:\\?[^\\s#]*)?$"
    );
    private static final String TEST_DATABASE_URL = requireIsolatedDatabaseUrl();
    private static final int EXPECTED_PORT = portFromUrl(TEST_DATABASE_URL);

    // These are the actual AppointmentMapper.xml queries, with JDBC parameters
    // in place of MyBatis bindings. Do not add index hints to the measured SQL.
    private static final String PAGE_SQL = """
            SELECT appointment.id, appointment.patient_id, appointment.slot_id,
                   appointment.status, appointment.confirm_deadline,
                   appointment.cancel_deadline
            FROM med_appointment appointment
            INNER JOIN med_patient patient
                ON patient.id = appointment.patient_id
            WHERE patient.owner_user_id = ?
            ORDER BY appointment.created_at DESC, appointment.id DESC
            LIMIT ? OFFSET ?
            """;
    private static final String COUNT_SQL = """
            SELECT COUNT(*)
            FROM med_appointment appointment
            INNER JOIN med_patient patient
                ON patient.id = appointment.patient_id
            WHERE patient.owner_user_id = ?
            """;
    private static final String UNIQUE_PATIENT_PROBE_SQL = """
            SELECT id FROM med_patient
            WHERE owner_user_id = ?
            LIMIT 2
            """;
    private static final String SINGLE_PATIENT_PAGE_SQL = """
            SELECT appointment.id, appointment.patient_id, appointment.slot_id,
                   appointment.status, appointment.confirm_deadline,
                   appointment.cancel_deadline
            FROM med_appointment appointment
            INNER JOIN med_patient patient
                ON patient.id = appointment.patient_id
            WHERE patient.id = ?
              AND patient.owner_user_id = ?
            ORDER BY appointment.created_at DESC, appointment.id DESC
            LIMIT ? OFFSET ?
            """;
    private static final String HISTORY_SQL = """
            SELECT history.id, history.from_status, history.to_status,
                   history.event_type, history.actor_user_id, history.created_at
            FROM med_appointment_status_log history
            INNER JOIN med_appointment appointment
                ON appointment.id = history.appointment_id
            INNER JOIN med_patient patient
                ON patient.id = appointment.patient_id
            WHERE history.appointment_id = ?
              AND patient.owner_user_id = ?
            ORDER BY history.created_at ASC, history.id ASC
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("medflow.security.jwt.secret", () -> TEST_ONLY_JWT_SECRET);
        registry.add("springdoc.api-docs.enabled", () -> "false");
        registry.add("springdoc.swagger-ui.enabled", () -> "false");
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void compareActualQueriesOnOneHundredThousandAppointments() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestampUtc", Instant.now().toString());
        report.put("scope", "local disposable MySQL; JDBC SELECT and full result fetch; not API latency");
        report.put("schema", TEST_SCHEMA);
        report.put("url", TEST_DATABASE_URL);
        report.put("warmupRounds", WARMUP_ROUNDS);
        report.put("measuredRounds", MEASURED_ROUNDS);
        report.put("order", "A/B on even rounds, B/A on odd rounds; A=optimizer ignores invisible index");
        report.put("candidateIndex", CANDIDATE_INDEX + " (patient_id, created_at DESC, id DESC) INVISIBLE");
        report.put("comparison", "The same invisible index is physically maintained in both phases; only optimizer use_invisible_indexes changes.");
        Path reportPath = Path.of("target", "sql-performance", "latest.json");
        try (Connection connection = dataSource.getConnection()) {
            verifyDisposableServer(connection);
            report.put("mysqlVersion", queryString(connection, "SELECT VERSION()"));
            report.put("mysqlPort", queryLong(connection, "SELECT @@port"));
            report.put("mysqlDataDirectory", queryString(connection, "SELECT @@datadir"));
            report.put("innodbBufferPoolBytes", queryLong(connection,
                    "SELECT @@innodb_buffer_pool_size"));
            report.put("isolation", queryString(connection,
                    "SELECT @@transaction_isolation"));
            try {
                Seed seed = seedData(connection);
                report.put("sampleDistribution", Map.of(
                        "singlePatientOwnerAppointments", 40_000,
                        "singlePatientOwnerPatients", 1,
                        "multiPatientOwnerAppointments", 30_000,
                        "multiPatientOwnerPatients", 3,
                        "backgroundAppointments", 30_000,
                        "backgroundPatients", 30,
                        "scheduleRows", SCHEDULES,
                        "slotRows", SCHEDULES * SLOTS_PER_SCHEDULE,
                        "historyRowsPerAppointment", 2));
                Map<String, Long> rowCounts = rowCounts(connection, seed);
                report.put("rowCounts", rowCounts);
                assertThat(rowCounts.get("appointments")).isEqualTo((long) APPOINTMENTS);
                assertThat(rowCounts.get("history")).isEqualTo(2L * APPOINTMENTS);
                assertThat(rowCounts.get("orphanPatients")).isZero();
                assertThat(rowCounts.get("orphanSlots")).isZero();
                assertThat(rowCounts.get("singleOwnerAppointments")).isEqualTo(40_000L);
                assertThat(rowCounts.get("multiOwnerAppointments")).isEqualTo(30_000L);
                assertThat(rowCounts.get("backgroundAppointments")).isEqualTo(30_000L);

                report.put("cursorPagination", benchmarkCursorPagination(connection, seed));
                report.put("productionServiceChecks", verifyServiceBranches(seed));

                // Flyway V8 created the production index. Hide it only in this
                // disposable server so A/B retains identical physical indexes.
                assertThat(queryString(connection, """
                        SELECT IS_VISIBLE
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'med_appointment'
                          AND INDEX_NAME = 'idx_appointment_patient_created_id'
                        LIMIT 1
                        """)).isEqualTo("YES");
                execute(connection, "ALTER TABLE med_appointment ALTER INDEX "
                        + CANDIDATE_INDEX + " INVISIBLE");
                execute(connection, "ANALYZE TABLE med_appointment,"
                        + " med_appointment_status_log, med_patient");
                report.put("appointmentIndexes", appointmentIndexes(connection));

                List<QuerySpec> queries = List.of(
                        new QuerySpec("singleOwnerFirstPage", PAGE_SQL,
                                new Object[]{seed.singleOwnerId(), 50, 0},
                                APPOINTMENTS - 60_000, 39_951L, true),
                        new QuerySpec("singleOwnerDeepPage", PAGE_SQL,
                                new Object[]{seed.singleOwnerId(), 50, 20_000},
                                20_000, 19_951L, true),
                        new QuerySpec("multiOwnerFirstPage", PAGE_SQL,
                                new Object[]{seed.multiOwnerId(), 50, 0},
                                70_000, 69_951L, true),
                        new QuerySpec("multiOwnerDeepPage", PAGE_SQL,
                                new Object[]{seed.multiOwnerId(), 50, 15_000},
                                55_000, 54_951L, true),
                        new QuerySpec("singleOwnerCount", COUNT_SQL,
                                new Object[]{seed.singleOwnerId()},
                                40_000, 40_000L, false),
                        new QuerySpec("multiOwnerCount", COUNT_SQL,
                                new Object[]{seed.multiOwnerId()},
                                30_000, 30_000L, false),
                        new QuerySpec("appointmentHistory", HISTORY_SQL,
                                new Object[]{40_000L, seed.singleOwnerId()},
                                2, null, false)
                );
                List<Map<String, Object>> queryReports = new ArrayList<>();
                report.put("queries", queryReports);
                Map<String, QueryEvidence> queryEvidence = new LinkedHashMap<>();
                for (QuerySpec query : queries) {
                    QueryEvidence evidence = benchmarkQuery(connection, query);
                    queryReports.add(evidence.report());
                    queryEvidence.put(query.name(), evidence);
                }
                List<Map<String, Object>> fastPathReports = new ArrayList<>();
                report.put("singlePatientFastPath", fastPathReports);
                fastPathReports.add(benchmarkSinglePatientFastPath(connection,
                        seed, 0,
                        queryEvidence.get("singleOwnerFirstPage")));
                fastPathReports.add(benchmarkSinglePatientFastPath(connection,
                        seed, 20_000,
                        queryEvidence.get("singleOwnerDeepPage")));
                setCandidateVisibleToOptimizer(connection, true);
                QueryResult multiOwnerProbe = runQuery(connection,
                        UNIQUE_PATIENT_PROBE_SQL,
                        new Object[]{seed.multiOwnerId()});
                assertThat(multiOwnerProbe.rows()).hasSize(2);
                report.put("multiPatientOwnerFastPath", Map.of(
                        "eligible", false,
                        "probeRows", multiOwnerProbe.rows(),
                        "probeSql", UNIQUE_PATIENT_PROBE_SQL,
                        "probeExplainAnalyzeTree", explainAnalyze(connection,
                                new QuerySpec("multiOwnerProbe",
                                        UNIQUE_PATIENT_PROBE_SQL,
                                        new Object[]{seed.multiOwnerId()},
                                        2, null, false)),
                        "fallback", "original owner-wide PAGE_SQL"));
            } catch (Exception | AssertionError failure) {
                report.put("failure", failure.getClass().getName() + ": "
                        + failure.getMessage());
                throw failure;
            } finally {
                Files.createDirectories(reportPath.getParent());
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(reportPath.toFile(), report);
            }
        }
    }

    private Map<String, Object> benchmarkCursorPagination(Connection connection, Seed seed)
            throws Exception {
        // The previous-page boundary is already known. This experiment excludes
        // its acquisition time and does not claim an arbitrary page-number jump.
        LocalDateTime boundaryTime;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT created_at FROM med_appointment WHERE id=20001");
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            boundaryTime = result.getTimestamp(1).toLocalDateTime();
        }
        AppointmentCursor cursor = new AppointmentCursor(boundaryTime, 20_001L);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("patientId", seed.singlePatientId());
        parameters.put("ownerUserId", seed.singleOwnerId());
        parameters.put("limit", 51);
        parameters.put("afterCreatedAt", boundaryTime);
        parameters.put("afterId", cursor.id());
        QuerySpec seek = mappedQuery("cursorAfter20000", parameters);
        parameters.put("afterCreatedAt", null);
        parameters.put("afterId", null);
        QuerySpec first = mappedQuery("cursorFirstPage", parameters);
        Object[] offsetParameters = Arrays.copyOf(first.parameters(),
                first.parameters().length + 1);
        offsetParameters[offsetParameters.length - 1] = 20_000;
        QuerySpec offset = new QuerySpec("offset20000SameProjection",
                first.sql().strip() + " OFFSET ?", offsetParameters, 51, null, true);

        List<Double> offsetMillis = new ArrayList<>();
        List<Double> cursorMillis = new ArrayList<>();
        List<Double> serviceMillis = new ArrayList<>();
        QueryResult reference = null;
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            QueryResult offsetResult;
            QueryResult seekResult;
            if (round % 2 == 0) {
                offsetResult = runQuery(connection, offset.sql(), offset.parameters());
                seekResult = runQuery(connection, seek.sql(), seek.parameters());
            } else {
                seekResult = runQuery(connection, seek.sql(), seek.parameters());
                offsetResult = runQuery(connection, offset.sql(), offset.parameters());
            }
            assertThat(seekResult.rows()).isEqualTo(offsetResult.rows());
            assertThat(seekResult.firstColumn()).hasSize(51)
                    .startsWith(20_000L).endsWith(19_950L);
            reference = seekResult;
            long start = System.nanoTime();
            AppointmentSlice slice = appointmentService.findOwnedSlice(
                    seed.singleOwnerId(), 50, cursor);
            double serviceTime = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(slice.items()).extracting(AppointmentCursorItem::id)
                    .containsExactlyElementsOf(seekResult.firstColumn().subList(0, 50));
            assertThat(slice.hasNext()).isTrue();
            assertThat(slice.nextCursor().id()).isEqualTo(19_951L);
            if (round >= WARMUP_ROUNDS) {
                offsetMillis.add(offsetResult.elapsedMillis());
                cursorMillis.add(seekResult.elapsedMillis());
                serviceMillis.add(serviceTime);
            }
        }
        AppointmentSlice multi = appointmentService.findOwnedSlice(seed.multiOwnerId(),
                50, null);
        assertThat(multi.items()).extracting(AppointmentCursorItem::id)
                .startsWith(70_000L).endsWith(69_951L);
        String seekPlan = explainAnalyze(connection, seek);
        String offsetPlan = explainAnalyze(connection, offset);
        assertThat(seekPlan).contains(CANDIDATE_INDEX)
                .contains("Index range scan").doesNotContain("Sort:");
        assertThat(seekPlan).containsPattern("actual time=[^\\n]* rows=51 loops=1");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "JDBC SELECT + full 51-row fetch; same projection/results;"
                + " known boundary acquisition excluded; no COUNT or HTTP");
        report.put("serviceScope", "real findOwnedSlice, including patient probe,"
                + " transaction and result mapping; no COUNT, total or HTTP");
        report.put("offsetSql", offset.sql());
        report.put("cursorSql", seek.sql());
        report.put("cursorCreatedAt", boundaryTime.toString());
        report.put("cursorId", cursor.id());
        report.put("offsetMillis", offsetMillis);
        report.put("cursorMillis", cursorMillis);
        report.put("cursorServiceMillis", serviceMillis);
        report.put("offsetMedianMillis", median(offsetMillis));
        report.put("cursorMedianMillis", median(cursorMillis));
        report.put("cursorServiceMedianMillis", median(serviceMillis));
        report.put("offsetExplainAnalyzeTree", offsetPlan);
        report.put("cursorExplainAnalyzeTree", seekPlan);
        report.put("fetchedRows", reference.rows().size());
        report.put("returnedRows", 50);
        report.put("hasNext", true);
        report.put("nextCursorId", 19_951L);
        report.put("resultIds", reference.firstColumn());
        report.put("resultSha256", sha256(reference.rows()));
        report.put("multiPatientFirstPageCorrect", true);
        return report;
    }

    private QuerySpec mappedQuery(String name, Map<String, Object> parameters) {
        BoundSql boundSql = sqlSessionFactory.getConfiguration()
                .getMappedStatement("com.qqlin.medflow.appointment.mapper."
                        + "AppointmentMapper.findSliceOwnedBySinglePatient")
                .getBoundSql(parameters);
        Object[] values = boundSql.getParameterMappings().stream()
                .map(mapping -> parameters.get(mapping.getProperty()))
                .map(value -> value instanceof LocalDateTime time
                        ? Timestamp.valueOf(time) : value)
                .toArray();
        return new QuerySpec(name, boundSql.getSql(), values, 51, null, true);
    }

    private Seed seedData(Connection connection) throws Exception {
        assertThat(queryLong(connection, "SELECT COUNT(*) FROM med_appointment"))
                .isZero();
        assertThat(queryLong(connection, "SELECT COUNT(*) FROM med_schedule"))
                .isZero();
        assertThat(queryLong(connection, "SELECT COUNT(*) FROM med_slot"))
                .isZero();
        long singleOwnerId = queryLong(connection,
                "SELECT id FROM sys_user WHERE username = 'patient.li'");
        long multiOwnerId = insertAndGetId(connection, """
                INSERT INTO sys_user (username, password_hash, role, status,
                                      token_version)
                SELECT ?, password_hash, 'PATIENT', 'ENABLED', 0
                FROM sys_user WHERE id = ?
                """, "performance.multi." + UUID.randomUUID(), singleOwnerId);
        long backgroundOwnerId = insertAndGetId(connection, """
                INSERT INTO sys_user (username, password_hash, role, status,
                                      token_version)
                SELECT ?, password_hash, 'PATIENT', 'ENABLED', 0
                FROM sys_user WHERE id = ?
                """, "performance.background." + UUID.randomUUID(), singleOwnerId);

        // MySQL cannot reopen one TEMPORARY table five times in this self-join.
        // The runner disposes this entire verified schema after the test.
        execute(connection, "CREATE TABLE benchmark_digit"
                + " (d TINYINT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=MEMORY");
        execute(connection, "INSERT INTO benchmark_digit(d) VALUES"
                + " (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
        execute(connection, "CREATE TEMPORARY TABLE benchmark_numbers"
                + " (n INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        execute(connection, """
                INSERT INTO benchmark_numbers(n)
                SELECT d4.d * 10000 + d3.d * 1000 + d2.d * 100
                       + d1.d * 10 + d0.d
                FROM benchmark_digit d0
                CROSS JOIN benchmark_digit d1
                CROSS JOIN benchmark_digit d2
                CROSS JOIN benchmark_digit d3
                CROSS JOIN benchmark_digit d4
                """);
        assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM benchmark_numbers"))
                .isEqualTo((long) APPOINTMENTS);

        execute(connection, "CREATE TEMPORARY TABLE benchmark_patient_range"
                + " (lower_n INT NOT NULL PRIMARY KEY, upper_n INT NOT NULL,"
                + " patient_id BIGINT UNSIGNED NOT NULL) ENGINE=InnoDB");
        List<Long> patientIds = new ArrayList<>();
        patientIds.add(insertPatient(connection, singleOwnerId, "hot"));
        for (int index = 0; index < 3; index++) {
            patientIds.add(insertPatient(connection, multiOwnerId,
                    "multi-" + index));
        }
        for (int index = 0; index < 30; index++) {
            patientIds.add(insertPatient(connection, backgroundOwnerId,
                    "background-" + index));
        }
        insertPatientRange(connection, 0, 39_999, patientIds.getFirst());
        for (int index = 0; index < 3; index++) {
            insertPatientRange(connection, 40_000 + index * 10_000,
                    49_999 + index * 10_000, patientIds.get(index + 1));
        }
        for (int index = 0; index < 30; index++) {
            insertPatientRange(connection, 70_000 + index * 1_000,
                    70_999 + index * 1_000, patientIds.get(index + 4));
        }

        long doctorUserId = queryLong(connection,
                "SELECT id FROM sys_user WHERE username = 'doctor.wang'");
        long templateId = queryLong(connection,
                "SELECT id FROM med_shift_template WHERE code = 'MORNING'");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long departmentId = insertAndGetId(connection,
                "INSERT INTO med_department(name,status) VALUES (?, 'ENABLED')",
                "performance-department-" + suffix);
        long doctorId = insertAndGetId(connection, """
                INSERT INTO med_doctor(user_id,department_id,name,status)
                VALUES (?,?,?,'ENABLED')
                """, doctorUserId, departmentId, "performance-doctor-" + suffix);
        long roomId = insertAndGetId(connection, """
                INSERT INTO med_clinic_room(department_id,name,status)
                VALUES (?,?,'ENABLED')
                """, departmentId, "performance-room-" + suffix);

        executeUpdate(connection, """
                INSERT INTO med_schedule
                    (id,doctor_id,clinic_room_id,shift_template_id,work_date,
                     start_time,end_time,slot_duration_minutes,
                     capacity_per_slot,status)
                SELECT n + 1, ?, ?, ?,
                       DATE_ADD(DATE('2025-01-01'), INTERVAL n DAY),
                       '08:00:00','12:00:00',30,50,'CLOSED'
                FROM benchmark_numbers WHERE n < ?
                """, doctorId, roomId, templateId, SCHEDULES);
        executeUpdate(connection, """
                INSERT INTO med_slot
                    (id,schedule_id,work_date,start_time,end_time,
                     booking_deadline,total_capacity,remaining_capacity,status)
                SELECT n + 1, FLOOR(n / ? ) + 1,
                       DATE_ADD(DATE('2025-01-01'),
                                INTERVAL FLOOR(n / ?) DAY),
                       SEC_TO_TIME(28800 + MOD(n, ?) * 1800),
                       SEC_TO_TIME(30600 + MOD(n, ?) * 1800),
                       '2024-12-31 00:00:00',50,50,'CLOSED'
                FROM benchmark_numbers WHERE n < ?
                """, SLOTS_PER_SCHEDULE, SLOTS_PER_SCHEDULE,
                SLOTS_PER_SCHEDULE, SLOTS_PER_SCHEDULE,
                SCHEDULES * SLOTS_PER_SCHEDULE);

        executeUpdate(connection, """
                INSERT INTO med_appointment
                    (id,patient_id,slot_id,status,confirm_deadline,
                     cancel_deadline,created_at)
                SELECT n.n + 1, p.patient_id, MOD(n.n, ?) + 1,
                       IF(MOD(n.n,2)=0,'CANCELLED','EXPIRED'),
                       DATE_ADD('2024-01-01 00:00:00',
                                INTERVAL (n.n * 30 + 1800) SECOND),
                       DATE_ADD('2024-01-01 00:00:00',
                                INTERVAL (n.n * 30 + 86400) SECOND),
                       DATE_ADD('2024-01-01 00:00:00',
                                INTERVAL (n.n * 30) SECOND)
                FROM benchmark_numbers n
                INNER JOIN benchmark_patient_range p
                    ON n.n BETWEEN p.lower_n AND p.upper_n
                """, SCHEDULES * SLOTS_PER_SCHEDULE);
        execute(connection, """
                INSERT INTO med_appointment_status_log
                    (appointment_id,from_status,to_status,event_type,
                     actor_user_id,created_at)
                SELECT a.id,NULL,'PENDING_CONFIRMATION','CREATED',
                       p.owner_user_id,a.created_at
                FROM med_appointment a
                INNER JOIN med_patient p ON p.id = a.patient_id
                """);
        execute(connection, """
                INSERT INTO med_appointment_status_log
                    (appointment_id,from_status,to_status,event_type,
                     actor_user_id,created_at)
                SELECT a.id,'PENDING_CONFIRMATION',a.status,
                       IF(a.status='CANCELLED','CANCELLED','EXPIRED'),
                       IF(a.status='CANCELLED',p.owner_user_id,NULL),
                       DATE_ADD(a.created_at, INTERVAL 1 HOUR)
                FROM med_appointment a
                INNER JOIN med_patient p ON p.id = a.patient_id
                """);
        return new Seed(singleOwnerId, multiOwnerId, backgroundOwnerId,
                patientIds.getFirst());
    }

    private Map<String, Long> rowCounts(Connection connection, Seed seed)
            throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("appointments", queryLong(connection,
                "SELECT COUNT(*) FROM med_appointment"));
        counts.put("history", queryLong(connection,
                "SELECT COUNT(*) FROM med_appointment_status_log"));
        counts.put("schedules", queryLong(connection,
                "SELECT COUNT(*) FROM med_schedule"));
        counts.put("slots", queryLong(connection,
                "SELECT COUNT(*) FROM med_slot"));
        counts.put("singleOwnerAppointments", queryLong(connection,
                COUNT_SQL, seed.singleOwnerId()));
        counts.put("multiOwnerAppointments", queryLong(connection,
                COUNT_SQL, seed.multiOwnerId()));
        counts.put("backgroundAppointments", queryLong(connection,
                COUNT_SQL, seed.backgroundOwnerId()));
        counts.put("orphanPatients", queryLong(connection, """
                SELECT COUNT(*) FROM med_appointment a
                LEFT JOIN med_patient p ON p.id = a.patient_id
                WHERE p.id IS NULL
                """));
        counts.put("orphanSlots", queryLong(connection, """
                SELECT COUNT(*) FROM med_appointment a
                LEFT JOIN med_slot s ON s.id = a.slot_id
                WHERE s.id IS NULL
                """));
        return counts;
    }

    private Map<String, Object> verifyServiceBranches(Seed seed) {
        assertThat(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(
                seed.singleOwnerId()))
                .containsExactly(seed.singlePatientId());
        assertThat(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(
                seed.multiOwnerId())).hasSize(2);
        assertThat(appointmentRepository.findPageOwnedBySinglePatient(
                seed.singlePatientId(), seed.multiOwnerId(), 0, 50))
                .as("a supplied patient id never bypasses ownership")
                .isEmpty();

        AppointmentPage singleFirst = appointmentService.findOwnedPage(
                seed.singleOwnerId(), 1, 50);
        AppointmentPage singleDeep = appointmentService.findOwnedPage(
                seed.singleOwnerId(), 401, 50);
        AppointmentPage singlePastEnd = appointmentService.findOwnedPage(
                seed.singleOwnerId(), 801, 50);
        AppointmentPage multiFirst = appointmentService.findOwnedPage(
                seed.multiOwnerId(), 1, 50);
        AppointmentPage multiDeep = appointmentService.findOwnedPage(
                seed.multiOwnerId(), 301, 50);
        AppointmentPage backgroundFirst = appointmentService.findOwnedPage(
                seed.backgroundOwnerId(), 1, 50);
        assertPage(singleFirst, 40_000, 40_000, 39_951);
        assertPage(singleDeep, 40_000, 20_000, 19_951);
        assertThat(singlePastEnd.total()).isEqualTo(40_000);
        assertThat(singlePastEnd.items()).isEmpty();
        assertPage(multiFirst, 30_000, 70_000, 69_951);
        assertPage(multiDeep, 30_000, 55_000, 54_951);
        assertPage(backgroundFirst, 30_000, 100_000, 99_951);

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("singleOwnerBranch", "exactly one patient; fast SQL");
        checks.put("singleFirstIds", singleFirst.items().stream()
                .map(Appointment::id).toList());
        checks.put("singleDeepIds", singleDeep.items().stream()
                .map(Appointment::id).toList());
        checks.put("singlePastEndEmpty", singlePastEnd.items().isEmpty());
        checks.put("multiOwnerBranch", "three patients; original SQL");
        checks.put("multiFirstIds", multiFirst.items().stream()
                .map(Appointment::id).toList());
        checks.put("multiDeepIds", multiDeep.items().stream()
                .map(Appointment::id).toList());
        checks.put("backgroundFirstIds", backgroundFirst.items().stream()
                .map(Appointment::id).toList());
        checks.put("crossOwnerPatientIdReturnsRows", false);
        return checks;
    }

    private void assertPage(AppointmentPage page, long total,
                            long firstId, long lastId) {
        assertThat(page.total()).isEqualTo(total);
        assertThat(page.items()).hasSize(50);
        List<Long> ids = page.items().stream().map(Appointment::id).toList();
        assertThat(ids.getFirst()).isEqualTo(firstId);
        assertThat(ids.getLast()).isEqualTo(lastId);
        for (int index = 1; index < ids.size(); index++) {
            assertThat(ids.get(index)).isEqualTo(ids.get(index - 1) - 1);
        }
    }

    private QueryEvidence benchmarkQuery(Connection connection,
                                         QuerySpec query) throws Exception {
        Map<String, Object> queryReport = new LinkedHashMap<>();
        queryReport.put("name", query.name());
        queryReport.put("sql", query.sql());
        queryReport.put("parameters", Arrays.asList(query.parameters()));
        Map<String, Object> modes = new LinkedHashMap<>();
        queryReport.put("modes", modes);
        List<Double> baselineTimes = new ArrayList<>();
        List<Double> candidateTimes = new ArrayList<>();
        QueryResult reference = null;
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            boolean[] order = round % 2 == 0
                    ? new boolean[]{false, true}
                    : new boolean[]{true, false};
            for (boolean useCandidate : order) {
                setCandidateVisibleToOptimizer(connection, useCandidate);
                QueryResult result = runQuery(connection, query.sql(),
                        query.parameters());
                if (reference == null) {
                    reference = result;
                    assertExpectedResult(query, result);
                } else {
                    assertThat(result.rows())
                            .as("same ordered rows for %s in every phase", query.name())
                            .isEqualTo(reference.rows());
                }
                if (round >= WARMUP_ROUNDS) {
                    (useCandidate ? candidateTimes : baselineTimes)
                            .add(result.elapsedMillis());
                }
            }
        }
        assertThat(reference).isNotNull();
        assertThat(baselineTimes).hasSize(MEASURED_ROUNDS);
        assertThat(candidateTimes).hasSize(MEASURED_ROUNDS);
        for (boolean useCandidate : new boolean[]{false, true}) {
            setCandidateVisibleToOptimizer(connection, useCandidate);
            String plan = explainAnalyze(connection, query);
            Map<String, Object> mode = new LinkedHashMap<>();
            List<Double> times = useCandidate
                    ? candidateTimes : baselineTimes;
            mode.put("optimizerSwitch",
                    queryString(connection, "SELECT @@session.optimizer_switch"));
            mode.put("measuredClientMillis", times);
            mode.put("medianClientMillis", median(times));
            mode.put("minClientMillis", times.stream()
                    .min(Comparator.naturalOrder()).orElseThrow());
            mode.put("maxClientMillis", times.stream()
                    .max(Comparator.naturalOrder()).orElseThrow());
            mode.put("explainAnalyzeTree", plan);
            mode.put("candidateIndexMentionedInPlan",
                    plan.contains(CANDIDATE_INDEX));
            mode.put("resultRowCount", reference.rows().size());
            mode.put("resultFirstColumnValues", reference.firstColumn());
            mode.put("resultSha256", sha256(reference.rows()));
            modes.put(useCandidate ? "candidateEnabled" : "baseline", mode);
        }
        return new QueryEvidence(queryReport, reference);
    }

    private Map<String, Object> benchmarkSinglePatientFastPath(
            Connection connection, Seed seed, int offset,
            QueryEvidence original) throws Exception {
        String name = offset == 0
                ? "singleOwnerFirstPageFastPath"
                : "singleOwnerDeepPageFastPath";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("precondition", "probe returns exactly one patient id; otherwise use original owner-wide query");
        result.put("probeSql", UNIQUE_PATIENT_PROBE_SQL);
        result.put("pageSql", SINGLE_PATIENT_PAGE_SQL);
        result.put("ownerUserId", seed.singleOwnerId());
        result.put("pageOffset", offset);
        result.put("pageSize", 50);
        List<Double> endToEndTimes = new ArrayList<>();
        List<Double> probeTimes = new ArrayList<>();
        List<Double> pageTimes = new ArrayList<>();
        for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS;
             round++) {
            setCandidateVisibleToOptimizer(connection, true);
            long startNanos = System.nanoTime();
            QueryResult probe = runQuery(connection, UNIQUE_PATIENT_PROBE_SQL,
                    new Object[]{seed.singleOwnerId()});
            assertThat(probe.firstColumn())
                    .containsExactly(seed.singlePatientId());
            QueryResult page = runQuery(connection, SINGLE_PATIENT_PAGE_SQL,
                    new Object[]{seed.singlePatientId(),
                            seed.singleOwnerId(), 50, offset});
            double endToEndMillis = (System.nanoTime() - startNanos)
                    / 1_000_000.0;
            assertThat(page.rows())
                    .as("fast path returns exactly the original ordered page")
                    .isEqualTo(original.reference().rows());
            if (round >= WARMUP_ROUNDS) {
                endToEndTimes.add(endToEndMillis);
                probeTimes.add(probe.elapsedMillis());
                pageTimes.add(page.elapsedMillis());
            }
        }
        assertThat(endToEndTimes).hasSize(MEASURED_ROUNDS);
        result.put("measuredEndToEndMillis", endToEndTimes);
        result.put("medianEndToEndMillis", median(endToEndTimes));
        result.put("measuredProbeMillis", probeTimes);
        result.put("measuredPageMillis", pageTimes);
        result.put("originalBaselineClientMillis",
                ((Map<?, ?>) ((Map<?, ?>) original.report().get("modes"))
                        .get("baseline")).get("measuredClientMillis"));
        result.put("resultRowCount", original.reference().rows().size());
        result.put("resultIds", original.reference().firstColumn());
        result.put("resultSha256", sha256(original.reference().rows()));
        setCandidateVisibleToOptimizer(connection, true);
        result.put("probeExplainAnalyzeTree", explainAnalyze(connection,
                new QuerySpec("singleOwnerProbe", UNIQUE_PATIENT_PROBE_SQL,
                        new Object[]{seed.singleOwnerId()}, 1, null, false)));
        String pagePlan = explainAnalyze(connection,
                new QuerySpec(name, SINGLE_PATIENT_PAGE_SQL,
                        new Object[]{seed.singlePatientId(),
                                seed.singleOwnerId(), 50, offset},
                        50, null, true));
        result.put("pageExplainAnalyzeTree", pagePlan);
        result.put("candidateIndexMentionedInPagePlan",
                pagePlan.contains(CANDIDATE_INDEX));
        return result;
    }

    private void assertExpectedResult(QuerySpec spec, QueryResult result) {
        if (spec.page()) {
            assertThat(result.rows()).hasSize(50);
            assertThat(result.firstColumn().getFirst())
                    .isEqualTo((long) spec.expected());
            assertThat(result.firstColumn().getLast())
                    .isEqualTo(spec.expectedLast());
            for (int index = 1; index < result.firstColumn().size(); index++) {
                assertThat(result.firstColumn().get(index))
                        .isEqualTo(result.firstColumn().get(index - 1) - 1);
            }
        } else if (spec.name().endsWith("Count")) {
            assertThat(result.rows()).hasSize(1);
            assertThat(result.firstColumn()).containsExactly((long) spec.expected());
        } else {
            assertThat(result.rows()).hasSize(spec.expected());
        }
    }

    private QueryResult runQuery(Connection connection, String sql,
                                 Object[] parameters) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        List<Long> firstColumn = new ArrayList<>();
        long startNanos = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(45);
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = metadata.getColumnCount();
                while (resultSet.next()) {
                    List<String> row = new ArrayList<>(columns);
                    for (int column = 1; column <= columns; column++) {
                        row.add(resultSet.getString(column));
                    }
                    rows.add(row);
                    firstColumn.add(resultSet.getLong(1));
                }
            }
        }
        double elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000.0;
        return new QueryResult(rows, firstColumn, elapsedMillis);
    }

    private String explainAnalyze(Connection connection, QuerySpec query)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "EXPLAIN ANALYZE FORMAT=TREE " + query.sql())) {
            statement.setQueryTimeout(60);
            bind(statement, query.parameters());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                String plan = result.getString(1);
                assertThat(plan).isNotBlank();
                return plan;
            }
        }
    }

    private void setCandidateVisibleToOptimizer(Connection connection,
                                                boolean visible) throws Exception {
        execute(connection, "SET SESSION optimizer_switch = 'use_invisible_indexes="
                + (visible ? "on" : "off") + "'");
    }

    private List<Map<String, Object>> appointmentIndexes(Connection connection)
            throws Exception {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,IS_VISIBLE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'med_appointment'
                ORDER BY INDEX_NAME,SEQ_IN_INDEX
                """)) {
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> index = new LinkedHashMap<>();
                    index.put("name", rows.getString(1));
                    index.put("sequence", rows.getInt(2));
                    index.put("column", rows.getString(3));
                    index.put("visible", rows.getString(4));
                    indexes.add(index);
                }
            }
        }
        List<Map<String, Object>> candidate = indexes.stream()
                .filter(index -> CANDIDATE_INDEX.equals(index.get("name")))
                .toList();
        assertThat(candidate).hasSize(3);
        assertThat(candidate.stream().map(index -> index.get("column")).toList())
                .containsExactly("patient_id", "created_at", "id");
        assertThat(candidate).allMatch(index -> "NO".equals(index.get("visible")));
        return indexes;
    }

    private long insertPatient(Connection connection, long ownerUserId,
                               String suffix) throws Exception {
        return insertAndGetId(connection, """
                INSERT INTO med_patient(owner_user_id,name,gender,birth_date,
                                        phone,relationship)
                VALUES (?,?,'UNKNOWN','2000-01-01','13800138000','FAMILY')
                """, ownerUserId, "performance-" + suffix);
    }

    private void insertPatientRange(Connection connection, int lower, int upper,
                                    long patientId) throws Exception {
        executeUpdate(connection, """
                INSERT INTO benchmark_patient_range(lower_n,upper_n,patient_id)
                VALUES (?,?,?)
                """, lower, upper, patientId);
    }

    private long insertAndGetId(Connection connection, String sql,
                                Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setQueryTimeout(60);
            bind(statement, parameters);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private int executeUpdate(Connection connection, String sql,
                              Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(120);
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(120);
            statement.execute(sql);
        }
    }

    private long queryLong(Connection connection, String sql,
                           Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(45);
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private void verifyDisposableServer(Connection connection) throws Exception {
        assertThat(queryString(connection, "SELECT DATABASE()"))
                .isEqualTo(TEST_SCHEMA);
        assertThat(queryLong(connection, "SELECT @@port"))
                .isEqualTo(EXPECTED_PORT);
        String dataDirectory = queryString(connection, "SELECT @@datadir")
                .replace('\\', '/').toLowerCase();
        assertThat(dataDirectory)
                .as("only the owned disposable MySQL data directory is permitted")
                .contains("/target/medflow-confirmation-test-")
                .endsWith("/data/");
        assertThat(connection.getAutoCommit()).isTrue();
    }

    private static void bind(PreparedStatement statement, Object[] parameters)
            throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static String sha256(List<List<String>> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (List<String> row : rows) {
            for (String cell : row) {
                byte[] bytes = (cell == null ? "<NULL>" : cell)
                        .getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String requireIsolatedDatabaseUrl() {
        String url = System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        Matcher matcher = url == null ? null : ISOLATED_URL_PATTERN.matcher(url);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException("MEDFLOW_CONFIRMATION_TEST_URL must be"
                    + " jdbc:mysql://127.0.0.1:<temporary-port>/"
                    + TEST_SCHEMA);
        }
        int port = Integer.parseInt(matcher.group(1));
        if (port > 65535 || port == 3306) {
            throw new IllegalStateException("A non-default disposable MySQL"
                    + " port is required: " + port);
        }
        return url;
    }

    private static int portFromUrl(String url) {
        Matcher matcher = ISOLATED_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unexpected test JDBC URL");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private record Seed(long singleOwnerId, long multiOwnerId,
                        long backgroundOwnerId, long singlePatientId) {
    }

    private record QuerySpec(String name, String sql, Object[] parameters,
                             int expected, Long expectedLast, boolean page) {
    }

    private record QueryResult(List<List<String>> rows,
                               List<Long> firstColumn,
                               double elapsedMillis) {
    }

    private record QueryEvidence(Map<String, Object> report,
                                 QueryResult reference) {
    }
}
