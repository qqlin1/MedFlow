package com.qqlin.medflow.billing;

import com.qqlin.medflow.billing.domain.*;
import com.qqlin.medflow.billing.dto.*;
import com.qqlin.medflow.billing.service.PrescriptionService;
import com.qqlin.medflow.encounter.dto.SaveMedicalRecordRequest;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.*;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static com.qqlin.medflow.billing.domain.PrescriptionStatus.*;

/** Real row-lock and transaction proof, restricted to a disposable loopback schema. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties = {"medflow.demo.accounts.enabled=false","medflow.appointment.expiration.enabled=false"})
class PrescriptionBillingMySqlIT {
    static final String URL=isolatedUrl();
    @Autowired PrescriptionService billing;
    @Autowired EncounterService encounters;
    @Autowired JdbcTemplate db;
    long ownerId,otherOwnerId,doctorUserId,otherDoctorUserId,departmentId,roomId,doctorId,otherDoctorId;
    long patientId,scheduleId,slotId,appointmentId,encounterId,drugId;
    String trigger;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->URL);
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",()->"");
        registry.add("spring.datasource.hikari.maximum-pool-size",()->"8");
        registry.add("spring.datasource.hikari.connection-init-sql",()->"SET SESSION innodb_lock_wait_timeout = 8");
        registry.add("medflow.security.jwt.secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("medflow_confirmation_test");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,20);
        ownerId=insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused','PATIENT')","rx.patient."+suffix);
        otherOwnerId=insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused','PATIENT')","rx.other."+suffix);
        doctorUserId=insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused','DOCTOR')","rx.doctor."+suffix);
        otherDoctorUserId=insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused','DOCTOR')","rx.doc2."+suffix);
        departmentId=insert("INSERT INTO med_department(name) VALUES(?)","rx.department."+suffix);
        roomId=insert("INSERT INTO med_clinic_room(department_id,name) VALUES(?,'Billing test room')",departmentId);
        doctorId=insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?,?,'Billing doctor')",doctorUserId,departmentId);
        otherDoctorId=insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?,?,'Other doctor')",otherDoctorUserId,departmentId);
        patientId=insert("INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship) VALUES(?,'Billing patient','UNKNOWN','2000-01-01','13800000000','SELF')",ownerId);
        scheduleId=insert("INSERT INTO med_schedule(doctor_id,clinic_room_id,work_date,start_time,end_time,status) VALUES(?,?,CURRENT_DATE(),'00:00:00','23:59:59','PUBLISHED')",doctorId,roomId);
        slotId=insert("INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity) VALUES(?,CURRENT_DATE(),'00:00:00','23:59:59',DATE_ADD(NOW(3),INTERVAL 1 HOUR),10,9)",scheduleId);
        appointmentId=insert("INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline) VALUES(?,?,'BOOKED',DATE_ADD(NOW(3),INTERVAL 1 HOUR),DATE_ADD(NOW(3),INTERVAL 1 HOUR))",patientId,slotId);
        encounterId=encounters.checkIn(ownerId,appointmentId).encounter().id();
        encounters.start(doctorUserId,encounterId);
        drugId=insert("INSERT INTO med_drug(code,name,specification,unit,unit_price,status) VALUES(?,'Snapshot drug','10mg','片',3.30,'ENABLED')","RX-"+suffix);
    }

    @AfterEach void cleanup() {
        if(trigger!=null) db.execute("DROP TRIGGER IF EXISTS "+trigger);
        db.update("DELETE mp FROM med_mock_payment mp JOIN med_bill b ON b.id=mp.bill_id JOIN med_prescription rx ON rx.id=b.prescription_id WHERE rx.encounter_id=?",encounterId);
        db.update("DELETE b FROM med_bill b JOIN med_prescription rx ON rx.id=b.prescription_id WHERE rx.encounter_id=?",encounterId);
        db.update("DELETE i FROM med_prescription_item i JOIN med_prescription rx ON rx.id=i.prescription_id WHERE rx.encounter_id=?",encounterId);
        db.update("DELETE FROM med_prescription WHERE encounter_id=?",encounterId);
        db.update("DELETE FROM med_drug WHERE id=?",drugId);
        db.update("DELETE FROM med_encounter_status_log WHERE encounter_id=?",encounterId);
        db.update("DELETE FROM med_medical_record WHERE encounter_id=?",encounterId);
        db.update("DELETE FROM med_encounter WHERE id=?",encounterId);
        db.update("DELETE FROM med_appointment_status_log WHERE appointment_id=?",appointmentId);
        db.update("DELETE FROM med_appointment WHERE id=?",appointmentId);
        db.update("DELETE FROM med_slot WHERE id=?",slotId);
        db.update("DELETE FROM med_schedule WHERE id=?",scheduleId);
        db.update("DELETE FROM med_patient WHERE id=?",patientId);
        db.update("DELETE FROM med_doctor WHERE id IN (?,?)",doctorId,otherDoctorId);
        db.update("DELETE FROM med_clinic_room WHERE id=?",roomId);
        db.update("DELETE FROM med_department WHERE id=?",departmentId);
        db.update("DELETE FROM sys_user WHERE id IN (?,?,?,?)",ownerId,otherOwnerId,doctorUserId,otherDoctorUserId);
    }

    @Test void savedSnapshotSurvivesCatalogEditsAndIssuedPrescriptionIsVisibleBeforeCompletion() {
        assertThat(billing.doctorDetail(doctorUserId,encounterId)).isNull();
        var draft=billing.save(doctorUserId,encounterId,request(0));
        long rx=draft.prescription().id();
        assertThat(draft.prescription().version()).isEqualTo(1);
        assertThat(draft.prescription().totalAmount()).isEqualByComparingTo("6.60");
        assertThat(billing.patientPage(ownerId,1,20).total()).isZero();
        fails(ErrorCode.PRESCRIPTION_NOT_FOUND,()->billing.patientDetail(ownerId,rx));
        db.update("UPDATE med_drug SET unit_price=99.99,name='Changed catalog name' WHERE id=?",drugId);
        var issued=billing.issue(doctorUserId,encounterId,1);
        assertThat(issued.items().getFirst().unitPrice()).isEqualByComparingTo("3.30");
        assertThat(issued.items().getFirst().drugName()).isEqualTo("Snapshot drug");
        assertThat(issued.bill().totalAmount()).isEqualByComparingTo("6.60");
        assertThat(billing.patientDetail(ownerId,rx).bill().status()).isEqualTo(BillStatus.UNPAID);
        assertThat(billing.patientPage(ownerId,1,20).items().getFirst().bill().id()).isEqualTo(issued.bill().id());
        assertThat(billing.patientPage(otherOwnerId,1,20).total()).isZero();
        assertThat(encounters.patientDetail(ownerId,appointmentId).medicalRecord()).isNull();
    }

    @Test void disabledDrugIsRejectedDuringBothSaveAndIssue() {
        db.update("UPDATE med_drug SET status='DISABLED' WHERE id=?",drugId);
        fails(ErrorCode.DRUG_DISABLED,()->billing.save(doctorUserId,encounterId,request(0)));
        assertThat(count("SELECT COUNT(*) FROM med_prescription WHERE encounter_id=?",encounterId)).isZero();
        db.update("UPDATE med_drug SET status='ENABLED' WHERE id=?",drugId);
        var draft=billing.save(doctorUserId,encounterId,request(0));
        db.update("UPDATE med_drug SET status='DISABLED' WHERE id=?",drugId);
        fails(ErrorCode.DRUG_DISABLED,()->billing.issue(doctorUserId,encounterId,1));
        assertThat(billing.doctorDetail(doctorUserId,encounterId).prescription().status()).isEqualTo(DRAFT);
        assertThat(count("SELECT COUNT(*) FROM med_bill WHERE prescription_id=?",draft.prescription().id())).isZero();
    }

    @Test void sameDraftVersionAcrossTwoTransactionsHasOneWinner() throws Exception {
        var outcomes=race(()->billing.save(doctorUserId,encounterId,request(0)),()->billing.save(doctorUserId,encounterId,request(0)));
        assertThat(outcomes.stream().filter(PrescriptionDetailResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
        assertThat(count("SELECT COUNT(*) FROM med_prescription WHERE encounter_id=?",encounterId)).isEqualTo(1);
        assertThat(billing.doctorDetail(doctorUserId,encounterId).items()).hasSize(1);
    }

    @Test void concurrentRepeatedIssueCreatesOnlyOneBill() throws Exception {
        long rx=billing.save(doctorUserId,encounterId,request(0)).prescription().id();
        var outcomes=race(()->billing.issue(doctorUserId,encounterId,1),()->billing.issue(doctorUserId,encounterId,1));
        assertThat(outcomes).allMatch(PrescriptionDetailResponse.class::isInstance);
        assertThat(count("SELECT COUNT(*) FROM med_bill WHERE prescription_id=?",rx)).isEqualTo(1);
        assertThat(billing.doctorDetail(doctorUserId,encounterId).prescription().version()).isEqualTo(2);
    }

    @Test void concurrentPaymentAndRetryRegisterOnePaymentUsingServerAmount() throws Exception {
        var issued=issued();
        long bill=issued.bill().id();
        var outcomes=race(()->billing.pay(ownerId,bill),()->billing.pay(ownerId,bill));
        assertThat(outcomes).allMatch(PrescriptionDetailResponse.class::isInstance);
        assertThat(billing.pay(ownerId,bill).bill().status()).isEqualTo(BillStatus.PAID);
        assertThat(count("SELECT COUNT(*) FROM med_mock_payment WHERE bill_id=?",bill)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT amount FROM med_mock_payment WHERE bill_id=?",java.math.BigDecimal.class,bill)).isEqualByComparingTo("6.60");
        assertThat(count("SELECT COUNT(*) FROM med_mock_payment mp JOIN med_bill b ON b.id=mp.bill_id WHERE b.id=? AND mp.paid_at=b.paid_at",bill)).isEqualTo(1);
    }

    @Test void otherDoctorAndOtherPatientCannotAccessOrPayOwnedResources() {
        var issued=issued();
        fails(ErrorCode.ENCOUNTER_NOT_FOUND,()->billing.doctorDetail(otherDoctorUserId,encounterId));
        fails(ErrorCode.ENCOUNTER_NOT_FOUND,()->billing.issue(otherDoctorUserId,encounterId,1));
        fails(ErrorCode.PRESCRIPTION_NOT_FOUND,()->billing.patientDetail(otherOwnerId,issued.prescription().id()));
        fails(ErrorCode.BILL_NOT_FOUND,()->billing.pay(otherOwnerId,issued.bill().id()));
        assertThat(billing.patientDetail(ownerId,issued.prescription().id()).bill().status()).isEqualTo(BillStatus.UNPAID);
    }

    @Test void draftCanBeDiscardedToCompleteWithoutACharge() {
        record();
        var draft=billing.save(doctorUserId,encounterId,request(0));
        fails(ErrorCode.PRESCRIPTION_DRAFT_EXISTS,()->encounters.complete(doctorUserId,encounterId,1));
        var discarded=billing.discard(doctorUserId,encounterId,1);
        assertThat(discarded.prescription().status()).isEqualTo(DISCARDED);
        assertThat(discarded.prescription().totalAmount()).isEqualByComparingTo("0.00");
        assertThat(discarded.items()).isEmpty();
        assertThat(discarded.bill()).isNull();
        assertThat(billing.discard(doctorUserId,encounterId,1).prescription().version()).isEqualTo(2);
        encounters.complete(doctorUserId,encounterId,1);
        fails(ErrorCode.PRESCRIPTION_STATE_CONFLICT,()->billing.save(doctorUserId,encounterId,request(2)));
        assertThat(count("SELECT COUNT(*) FROM med_bill WHERE prescription_id=?",draft.prescription().id())).isZero();
    }

    @Test void discardedDraftReopensWithSameIdAndNoVersionResetAndPaymentDoesNotGateCompletion() {
        record();
        long rx=billing.save(doctorUserId,encounterId,request(0)).prescription().id();
        billing.discard(doctorUserId,encounterId,1);
        fails(ErrorCode.PRESCRIPTION_VERSION_CONFLICT,()->billing.save(doctorUserId,encounterId,request(1)));
        var reopened=billing.save(doctorUserId,encounterId,request(2));
        assertThat(reopened.prescription().id()).isEqualTo(rx);
        assertThat(reopened.prescription().version()).isEqualTo(3);
        var issued=billing.issue(doctorUserId,encounterId,3);
        encounters.complete(doctorUserId,encounterId,1);
        assertThat(billing.issue(doctorUserId,encounterId,3).prescription().version()).isEqualTo(4);
        assertThat(billing.pay(ownerId,issued.bill().id()).bill().status()).isEqualTo(BillStatus.PAID);
    }

    @Test void completeAndFirstDraftSaveCannotBothCommit() throws Exception {
        record();
        var outcomes=race(()->encounters.complete(doctorUserId,encounterId,1),()->billing.save(doctorUserId,encounterId,request(0)));
        assertThat(outcomes.stream().filter(ErrorCode.class::isInstance).count()).isEqualTo(1);
        String status=db.queryForObject("SELECT status FROM med_encounter WHERE id=?",String.class,encounterId);
        if("COMPLETED".equals(status)) {
            assertThat(count("SELECT COUNT(*) FROM med_prescription WHERE encounter_id=?",encounterId)).isZero();
            assertThat(outcomes).contains(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
        } else {
            assertThat(status).isEqualTo("IN_PROGRESS");
            assertThat(outcomes).contains(ErrorCode.PRESCRIPTION_DRAFT_EXISTS);
            assertThat(billing.doctorDetail(doctorUserId,encounterId).prescription().status()).isEqualTo(DRAFT);
        }
    }

    @Test void issueAndBillCreationRollBackTogether() {
        long rx=billing.save(doctorUserId,encounterId,request(0)).prescription().id();
        trigger="test_rx_bill_"+UUID.randomUUID().toString().replace("-","");
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON med_bill FOR EACH ROW BEGIN IF NEW.prescription_id="+rx+
                " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test bill insert failure'; END IF; END");
        assertThatThrownBy(()->billing.issue(doctorUserId,encounterId,1)).isInstanceOf(DataAccessException.class);
        assertThat(billing.doctorDetail(doctorUserId,encounterId).prescription().status()).isEqualTo(DRAFT);
        assertThat(billing.doctorDetail(doctorUserId,encounterId).prescription().version()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM med_bill WHERE prescription_id=?",rx)).isZero();
    }

    @Test void paymentRecordFailureRollsBillBackToUnpaid() {
        var issued=issued();
        long bill=issued.bill().id();
        trigger="test_rx_payment_"+UUID.randomUUID().toString().replace("-","");
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON med_mock_payment FOR EACH ROW BEGIN IF NEW.bill_id="+bill+
                " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test payment insert failure'; END IF; END");
        assertThatThrownBy(()->billing.pay(ownerId,bill)).isInstanceOf(DataAccessException.class);
        assertThat(billing.patientDetail(ownerId,issued.prescription().id()).bill().status()).isEqualTo(BillStatus.UNPAID);
        assertThat(billing.patientDetail(ownerId,issued.prescription().id()).bill().paidAt()).isNull();
        assertThat(count("SELECT COUNT(*) FROM med_mock_payment WHERE bill_id=?",bill)).isZero();
    }

    private void record() {
        encounters.saveMedicalRecord(doctorUserId,encounterId,new SaveMedicalRecordRequest("Test complaint","Test history","Test diagnosis","Test advice",0L));
    }
    private PrescriptionDetailResponse issued() {
        billing.save(doctorUserId,encounterId,request(0));
        return billing.issue(doctorUserId,encounterId,1);
    }
    private SavePrescriptionRequest request(long version) {
        return new SavePrescriptionRequest(version,List.of(new SavePrescriptionRequest.Item(drugId,2,"每日一次")));
    }
    private long insert(String sql,Object... parameters) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        db.update(connection->{
            PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<parameters.length;i++) statement.setObject(i+1,parameters[i]);
            return statement;
        },key);
        return key.getKey().longValue();
    }
    private long count(String sql,Object... parameters) { return db.queryForObject(sql,Long.class,parameters); }
    private List<Object> race(Supplier<?> one,Supplier<?> two) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            Future<Object> first=executor.submit(()->{gate.await();return outcome(one);});
            Future<Object> second=executor.submit(()->{gate.await();return outcome(two);});
            gate.countDown();
            return List.of(first.get(12,TimeUnit.SECONDS),second.get(12,TimeUnit.SECONDS));
        }
    }
    private Object outcome(Supplier<?> action) {
        try{return action.get();}catch(BusinessException exception){return exception.getErrorCode();}
    }
    private void fails(ErrorCode code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private static String isolatedUrl() {
        String url=System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match=Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$").matcher(url==null?"":url);
        if(!match.matches() || Integer.parseInt(match.group(1))==3306 || Integer.parseInt(match.group(1))>65535)
            throw new IllegalStateException("Refusing normal database; use isolated tools/test-confirmation.ps1");
        return url;
    }
}
