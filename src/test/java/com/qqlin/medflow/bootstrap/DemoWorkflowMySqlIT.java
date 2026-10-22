package com.qqlin.medflow.bootstrap;

import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.billing.domain.BillStatus;
import com.qqlin.medflow.billing.dto.SavePrescriptionRequest;
import com.qqlin.medflow.billing.service.PrescriptionService;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.SaveMedicalRecordRequest;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.organization.detail.DoctorDetailRepository;
import com.qqlin.medflow.organization.detail.DoctorDetailService;
import com.qqlin.medflow.scheduling.management.ScheduleManagementService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Ordered stages exercise one startup-generated demo dataset. The normal
 * database is refused; the launcher deletes its own disposable schema/process.
 */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(properties={"medflow.demo.accounts.enabled=true","medflow.demo.workflow.enabled=true",
        "medflow.appointment.expiration.enabled=false","medflow.doctor-cache.enabled=false"})
class DemoWorkflowMySqlIT {
    private static final String URL=isolatedUrl();
    @Autowired DemoWorkflowService workflows;
    @Autowired JdbcTemplate db;
    @Autowired EncounterService encounters;
    @Autowired PrescriptionService prescriptions;
    @Autowired AppointmentService appointments;
    @Autowired ScheduleManagementService schedules;
    @Autowired DoctorDetailService profiles;
    @Autowired DoctorDetailRepository profileRepository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->URL);
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",()->"");
        registry.add("spring.datasource.hikari.connection-init-sql",()->"SET SESSION innodb_lock_wait_timeout=8");
        registry.add("medflow.security.jwt.secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void verifyIsolation() {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("medflow_confirmation_test");
    }

    @Test @Order(1)
    void startupCreatesAnActionableDatasetForAllThreeRolesWithConsistentHistoryAndCapacity() {
        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE (username='admin' AND role='ADMIN') OR (username='doctor' AND role='DOCTOR') OR (username='patient' AND role='PATIENT')")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM med_patient WHERE owner_user_id=? AND name LIKE '演示·%'",owner())).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM med_drug WHERE code IN ('DEMO-001','DEMO-002','DEMO-003','DEMO-004','DEMO-005') AND status='ENABLED'")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM med_schedule WHERE doctor_id=? AND work_date BETWEEN DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY) AND DATE_ADD(CURRENT_DATE(),INTERVAL 7 DAY) AND status='PUBLISHED'",doctor())).isEqualTo(14);
        assertThat(count("SELECT COUNT(*) FROM med_schedule WHERE doctor_id=? AND work_date>CURRENT_DATE() AND status='DRAFT'",doctor())).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id JOIN med_slot sl ON sl.id=a.slot_id WHERE e.doctor_id=? AND sl.work_date=CURRENT_DATE() AND e.status='WAITING'",doctor())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND status='COMPLETED'",doctor())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND completed_at>NOW(3)",doctor())).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM med_encounter a JOIN med_encounter b ON b.doctor_id=a.doctor_id AND b.id>a.id
                WHERE a.doctor_id=? AND a.status='COMPLETED' AND b.status='COMPLETED'
                  AND a.started_at<b.completed_at AND a.completed_at>b.started_at
                """,doctor())).isZero();
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND status='IN_PROGRESS'",doctor())).isZero();
        assertThat(count("SELECT COUNT(*) FROM med_encounter WHERE doctor_id=? AND status='SKIPPED'",doctor())).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id
                JOIN med_slot sl ON sl.id=a.slot_id WHERE e.doctor_id=? AND
                (DATE(e.checked_in_at)<>sl.work_date OR e.checked_in_at<TIMESTAMP(sl.work_date,sl.start_time)-INTERVAL 30 MINUTE
                    OR e.checked_in_at>=TIMESTAMP(sl.work_date,sl.end_time))
                """,doctor())).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id WHERE s.doctor_id=?
                AND sl.remaining_capacity<>sl.total_capacity-(SELECT COUNT(*) FROM med_appointment a WHERE a.slot_id=sl.id AND a.active_flag=1)
                """,doctor())).isZero();
        assertThat(prescriptions.patientPage(owner(),1,20).items()).hasSize(4);
        assertThat(count("SELECT COUNT(*) FROM med_bill b JOIN med_prescription rx ON rx.id=b.prescription_id JOIN med_encounter e ON e.id=rx.encounter_id WHERE e.doctor_id=? AND b.status='UNPAID'",doctor())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_bill b JOIN med_prescription rx ON rx.id=b.prescription_id JOIN med_encounter e ON e.id=rx.encounter_id WHERE e.doctor_id=? AND b.status='PAID'",doctor())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_mock_payment mp JOIN med_bill b ON b.id=mp.bill_id WHERE mp.paid_by_user_id=? AND mp.amount=b.total_amount AND mp.paid_at=b.paid_at",owner())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM med_appointment_status_log l JOIN med_appointment a ON a.id=l.appointment_id JOIN med_patient p ON p.id=a.patient_id WHERE p.owner_user_id=? AND p.name LIKE '演示·%'",owner())).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM med_encounter_status_log l JOIN med_encounter e ON e.id=l.encounter_id WHERE e.doctor_id=?",doctor())).isEqualTo(10);
        assertThat(count("""
                SELECT COUNT(*) FROM med_prescription rx JOIN med_encounter e ON e.id=rx.encounter_id
                WHERE e.doctor_id=? AND rx.total_amount<>(SELECT SUM(i.line_amount) FROM med_prescription_item i WHERE i.prescription_id=rx.id)
                """,doctor())).isZero();
    }

    @Test @Order(7)
    void directoryAndSamePatientCrossDoctorHistoryAreSeededWithoutOverwritingChanges() {
        assertThat(count("SELECT COUNT(*) FROM med_doctor d JOIN sys_user u ON u.id=d.user_id WHERE u.username IN ('demo.internal','demo.surgery','demo.derm') AND d.title<>'' AND d.specialty<>'' AND d.introduction<>''")).isEqualTo(3);
        long patient=count("SELECT id FROM med_patient WHERE owner_user_id=? AND name='演示·候诊一（虚构）'",owner());
        assertThat(count("SELECT COUNT(DISTINCT e.doctor_id) FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id WHERE a.patient_id=? AND e.status='COMPLETED'",patient)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id WHERE a.patient_id=? AND e.completed_at>NOW(3)",patient)).isZero();
        long dermatologist=count("SELECT d.id FROM med_doctor d JOIN sys_user u ON u.id=d.user_id WHERE u.username='demo.derm'");
        db.update("UPDATE med_doctor SET introduction='管理员已维护的简介' WHERE id=?",dermatologist);
        var before=tableCounts();
        workflows.initialize();
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT introduction FROM med_doctor WHERE id=?",String.class,dermatologist)).isEqualTo("管理员已维护的简介");
    }

    @Test @Order(8)
    void publicBiographyCanBeMaintainedSearchedAndRolledBackWithoutLosingOmittedFields() {
        long id=doctor();
        String originalName=profileRepository.findEnabled(id).orElseThrow().name();
        var saved=profiles.updateProfile(id,originalName,"主治医师（演示）","学习验收专属擅长","【虚构演示】学习验收简介");
        assertThat(saved.title()).isEqualTo("主治医师（演示）");
        assertThat(profiles.directory(null,"学习验收专属")).extracting(v->v.id()).contains(id);
        assertThat(profiles.directory(saved.departmentId(),"学习验收专属")).hasSize(1);
        profiles.updateProfile(id,originalName,null,null,null);
        assertThat(profileRepository.findEnabled(id).orElseThrow().introduction()).isEqualTo("【虚构演示】学习验收简介");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->{profiles.updateProfile(id,originalName,"回滚职称","回滚擅长","回滚简介");status.setRollbackOnly();});
        assertThat(profileRepository.findEnabled(id).orElseThrow().specialty()).isEqualTo("学习验收专属擅长");
        assertThat(profiles.directory(null,"%")).isEmpty(); // literal search, not an injected LIKE wildcard
        assertThat(profiles.directory(null,"虚构")).hasSize(3);
    }

    @Test @Order(2)
    void repeatedInitializationDoesNotIncreaseCountsOrRestoreUserChanges() {
        long waiting=encounterFor("演示·候诊一（虚构）");
        encounters.skip(doctorUser(),waiting);
        LocalDate tomorrow=today().plusDays(1);
        long future=count("SELECT entity_id FROM med_demo_seed_registry WHERE seed_key=?","future-booking:"+doctor()+":"+tomorrow);
        appointments.cancel(owner(),future);
        long afternoon=count("SELECT id FROM med_schedule WHERE doctor_id=? AND work_date=? AND start_time='13:00:00' AND status='PUBLISHED'",doctor(),tomorrow);
        schedules.cancel(afternoon,admin(),"演示测试：用户主动取消，重启不能恢复");
        Map<String,Long> before=tableCounts();
        var repeated=workflows.initialize();
        workflows.initialize();
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(repeated.seededToday()).isFalse();
        assertThat(repeated.schedulesCreated()).isZero();
        assertThat(repeated.appointmentsCreated()).isZero();
        assertThat(db.queryForObject("SELECT status FROM med_encounter WHERE id=?",String.class,waiting)).isEqualTo("SKIPPED");
        assertThat(db.queryForObject("SELECT status FROM med_appointment WHERE id=?",String.class,future)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT status FROM med_schedule WHERE id=?",String.class,afternoon)).isEqualTo("CANCELLED");
    }

    @Test @Order(3)
    void tomorrowAndFollowingDaysHaveBookableFiniteCapacity() {
        assertThat(count("""
                SELECT COUNT(*) FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id
                WHERE s.doctor_id=? AND sl.work_date=DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY)
                  AND s.status='PUBLISHED' AND sl.status='OPEN' AND sl.remaining_capacity>0 AND sl.booking_deadline>NOW(3)
                """,doctor())).isGreaterThanOrEqualTo(8);
        assertThat(count("""
                SELECT COUNT(DISTINCT sl.work_date) FROM med_slot sl JOIN med_schedule s ON s.id=sl.schedule_id
                WHERE s.doctor_id=? AND sl.work_date BETWEEN DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY) AND DATE_ADD(CURRENT_DATE(),INTERVAL 7 DAY)
                  AND s.status='PUBLISHED' AND sl.status='OPEN' AND sl.remaining_capacity>0
                """,doctor())).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM med_schedule WHERE doctor_id=? AND start_time='00:00:00'",doctor())).isZero();
    }

    @Test @Order(4)
    void seededWaitingEncounterCanRunThroughRealDoctorPrescriptionCompletionAndPatientPaymentServices() {
        long waiting=encounterFor("演示·候诊二（虚构）");
        LocalDateTime start=db.queryForObject("""
                SELECT TIMESTAMP(sl.work_date,sl.start_time) FROM med_encounter e
                JOIN med_appointment a ON a.id=e.appointment_id JOIN med_slot sl ON sl.id=a.slot_id WHERE e.id=?
                """,LocalDateTime.class,waiting);
        LocalDateTime now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);
        Assumptions.assumeFalse(now.isBefore(start),"Normal morning demo queue can be started after 08:00; production time rules stay unchanged");
        assertThat(encounters.start(doctorUser(),waiting).encounter().status()).isEqualTo(EncounterStatus.IN_PROGRESS);
        var saved=encounters.saveMedicalRecord(doctorUser(),waiting,new SaveMedicalRecordRequest("【演示测试】主诉","模拟现病史","模拟诊断","模拟医嘱",0L));
        long drug=count("SELECT id FROM med_drug WHERE code='DEMO-001'");
        var draft=prescriptions.save(doctorUser(),waiting,new SavePrescriptionRequest(0L,List.of(new SavePrescriptionRequest.Item(drug,1,"仅演示"))));
        var issued=prescriptions.issue(doctorUser(),waiting,draft.prescription().version());
        assertThat(issued.bill().status()).isEqualTo(BillStatus.UNPAID);
        assertThat(encounters.complete(doctorUser(),waiting,saved.medicalRecord().version()).encounter().status()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(prescriptions.pay(owner(),issued.bill().id()).bill().status()).isEqualTo(BillStatus.PAID);
        var before=tableCounts();
        workflows.initialize();
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(encounters.doctorDetail(doctorUser(),waiting).encounter().status()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(prescriptions.patientDetail(owner(),issued.prescription().id()).bill().status()).isEqualTo(BillStatus.PAID);
    }

    @Test @Order(5)
    void newClinicalSeedUsesOnlyEnabledDemoDrugsAndPreservesDisabledStatus() {
        db.update("UPDATE med_drug SET status='DISABLED',version=version+1 WHERE code IN ('DEMO-001','DEMO-002')");
        long priorPrescription=count("SELECT COALESCE(MAX(id),0) FROM med_prescription");
        // Test-only registry manipulation models a fresh daily fixture cycle;
        // existing encounters/appointments keep their actual state and history.
        clearClinicalMarkersForFreshCycle();
        var refreshed=workflows.initialize();
        assertThat(refreshed.prescriptionsCreated()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM med_drug WHERE code IN ('DEMO-001','DEMO-002') AND status='DISABLED'")).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM med_prescription_item i JOIN med_prescription rx ON rx.id=i.prescription_id
                JOIN med_drug d ON d.id=i.drug_id WHERE rx.id>? AND rx.status='ISSUED' AND d.status='DISABLED'
                """,priorPrescription)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM med_prescription_item i JOIN med_prescription rx ON rx.id=i.prescription_id
                JOIN med_drug d ON d.id=i.drug_id WHERE rx.id>? AND d.code IN ('DEMO-003','DEMO-004')
                """,priorPrescription)).isEqualTo(4);
    }

    @Test @Order(6)
    void allDisabledDemoDrugsSkipNewPrescriptionsAndPaymentsWithoutChangingCatalog() {
        db.update("UPDATE med_drug SET status='DISABLED',version=version+1 WHERE code IN ('DEMO-001','DEMO-002','DEMO-003','DEMO-004','DEMO-005')");
        long beforePrescriptions=count("SELECT COUNT(*) FROM med_prescription");
        long beforeBills=count("SELECT COUNT(*) FROM med_bill");
        long beforePayments=count("SELECT COUNT(*) FROM med_mock_payment");
        clearClinicalMarkersForFreshCycle();
        var refreshed=workflows.initialize();
        assertThat(refreshed.encountersCreated()).isEqualTo(5);
        assertThat(refreshed.prescriptionsCreated()).isZero();
        assertThat(refreshed.paymentsCreated()).isZero();
        assertThat(refreshed.message()).contains("演示药品均已停用");
        assertThat(count("SELECT COUNT(*) FROM med_prescription")).isEqualTo(beforePrescriptions);
        assertThat(count("SELECT COUNT(*) FROM med_bill")).isEqualTo(beforeBills);
        assertThat(count("SELECT COUNT(*) FROM med_mock_payment")).isEqualTo(beforePayments);
        assertThat(count("SELECT COUNT(*) FROM med_drug WHERE code IN ('DEMO-001','DEMO-002','DEMO-003','DEMO-004','DEMO-005') AND status='DISABLED'")).isEqualTo(5);
    }

    private void clearClinicalMarkersForFreshCycle() {
        String key="clinical:"+doctor()+":"+today();
        db.update("DELETE FROM med_demo_seed_registry WHERE seed_key IN (?,?,?)",key,key+":completed",key+":queue");
    }

    private long encounterFor(String name) {
        return count("""
                SELECT e.id FROM med_encounter e JOIN med_appointment a ON a.id=e.appointment_id
                JOIN med_patient p ON p.id=a.patient_id JOIN med_slot sl ON sl.id=a.slot_id
                WHERE p.owner_user_id=? AND p.name=? AND e.doctor_id=?
                  AND e.status='WAITING' AND sl.work_date=CURRENT_DATE()
                ORDER BY e.id DESC LIMIT 1
                """,owner(),name,doctor());
    }
    private Map<String,Long> tableCounts() {
        Map<String,Long> result=new LinkedHashMap<>();
        for(String table:List.of("med_schedule","med_slot","med_patient","med_drug","med_appointment","med_encounter",
                "med_medical_record","med_prescription","med_prescription_item","med_bill","med_mock_payment","med_demo_seed_registry"))
            result.put(table,count("SELECT COUNT(*) FROM "+table));
        return result;
    }
    private LocalDate today() { return db.queryForObject("SELECT CURRENT_DATE()",LocalDate.class); }
    private long owner() { return count("SELECT id FROM sys_user WHERE username='patient'"); }
    private long doctorUser() { return count("SELECT id FROM sys_user WHERE username='doctor'"); }
    private long doctor() { return count("SELECT id FROM med_doctor WHERE user_id=?",doctorUser()); }
    private long admin() { return count("SELECT id FROM sys_user WHERE username='admin'"); }
    private long count(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
    private static String isolatedUrl() {
        String url=System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match=Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$").matcher(url==null?"":url);
        if(!match.matches() || Integer.parseInt(match.group(1))==3306 || Integer.parseInt(match.group(1))>65535)
            throw new IllegalStateException("Refusing normal database; use isolated tools/test-confirmation.ps1");
        return url;
    }
}
