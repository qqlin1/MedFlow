package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.billing.domain.PrescriptionStatus;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Real SQL ownership/visibility checks; only a disposable loopback schema is accepted. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(properties={"medflow.demo.accounts.enabled=false","medflow.demo.workflow.enabled=false",
        "medflow.appointment.expiration.enabled=false"})
class PatientHistoryMySqlIT {
    static final String URL=isolatedUrl();
    @Autowired PatientHistoryService history;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    long owner,otherOwner,doctorUser,secondDoctorUser,unrelatedDoctorUser,department,room,doctor,secondDoctor,unrelatedDoctor;
    long patient,otherPatient,anchor,completed,draft,pending,cancelled,crossPatient;
    long completedEncounter,draftEncounter,otherEncounter,completedRx,draftRx,drug;
    final List<Long> scheduleIds=new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->URL);registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",()->"");
        registry.add("medflow.security.jwt.secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("medflow_confirmation_test");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,20);
        owner=user("hist.owner."+suffix,"PATIENT");otherOwner=user("hist.other."+suffix,"PATIENT");
        doctorUser=user("hist.doctor."+suffix,"DOCTOR");secondDoctorUser=user("hist.second."+suffix,"DOCTOR");
        unrelatedDoctorUser=user("hist.unrelated."+suffix,"DOCTOR");
        department=insert("INSERT INTO med_department(name) VALUES(?)","hist.department."+suffix);
        room=insert("INSERT INTO med_clinic_room(department_id,name) VALUES(?,'History room')",department);
        doctor=doctor(doctorUser,"Current doctor");secondDoctor=doctor(secondDoctorUser,"Historical doctor");
        unrelatedDoctor=doctor(unrelatedDoctorUser,"Unrelated doctor");
        patient=patient("Longitudinal patient");otherPatient=patient("Different patient, same login owner");
        anchor=appointment(patient,doctor,0,"08:00:00","08:30:00","BOOKED");
        completed=appointment(patient,secondDoctor,-1,"08:00:00","08:30:00","BOOKED");
        draft=appointment(patient,secondDoctor,0,"09:00:00","09:30:00","BOOKED");
        pending=appointment(patient,doctor,1,"08:00:00","08:30:00","PENDING_CONFIRMATION");
        cancelled=appointment(patient,doctor,2,"08:00:00","08:30:00","CANCELLED");
        crossPatient=appointment(otherPatient,secondDoctor,-2,"08:00:00","08:30:00","BOOKED");
        completedEncounter=encounter(completed,secondDoctor,"COMPLETED","Historical completed complaint");
        draftEncounter=encounter(draft,secondDoctor,"IN_PROGRESS","SECRET unfinished complaint");
        otherEncounter=encounter(crossPatient,secondDoctor,"COMPLETED","SECRET different patient complaint");
        drug=insert("INSERT INTO med_drug(code,name,specification,unit,unit_price) VALUES(?,'History medicine','10mg','片',3.30)","HIS-"+suffix);
        completedRx=prescription(completedEncounter,"ISSUED",2);
        draftRx=prescription(draftEncounter,"DRAFT",1);
    }

    @AfterEach void cleanup() {
        db.update("DELETE mp FROM med_mock_payment mp JOIN med_bill b ON b.id=mp.bill_id WHERE b.prescription_id IN (?,?)",completedRx,draftRx);
        db.update("DELETE FROM med_bill WHERE prescription_id IN (?,?)",completedRx,draftRx);
        db.update("DELETE FROM med_prescription_item WHERE prescription_id IN (?,?)",completedRx,draftRx);
        db.update("DELETE FROM med_prescription WHERE id IN (?,?)",completedRx,draftRx);
        db.update("DELETE FROM med_drug WHERE id=?",drug);
        db.update("DELETE FROM med_encounter_status_log WHERE encounter_id IN (?,?,?)",completedEncounter,draftEncounter,otherEncounter);
        db.update("DELETE FROM med_medical_record WHERE encounter_id IN (?,?,?)",completedEncounter,draftEncounter,otherEncounter);
        db.update("DELETE FROM med_encounter WHERE id IN (?,?,?)",completedEncounter,draftEncounter,otherEncounter);
        for(long appointment:List.of(anchor,completed,draft,pending,cancelled,crossPatient)) {
            db.update("DELETE FROM med_appointment_status_log WHERE appointment_id=?",appointment);
            db.update("DELETE FROM med_appointment WHERE id=?",appointment);
        }
        for(long schedule:scheduleIds) {db.update("DELETE FROM med_slot WHERE schedule_id=?",schedule);db.update("DELETE FROM med_schedule WHERE id=?",schedule);}
        db.update("DELETE FROM med_patient WHERE id IN (?,?)",patient,otherPatient);
        db.update("DELETE FROM med_doctor WHERE id IN (?,?,?)",doctor,secondDoctor,unrelatedDoctor);
        db.update("DELETE FROM med_clinic_room WHERE id=?",room);db.update("DELETE FROM med_department WHERE id=?",department);
        db.update("DELETE FROM sys_user WHERE id IN (?,?,?,?,?)",owner,otherOwner,doctorUser,secondDoctorUser,unrelatedDoctorUser);
    }

    @Test void patientHistoryIsLongitudinalAndContactIsMasked() {
        var page=history.patientPage(owner,patient,1,20);
        assertThat(page.patient().id()).isEqualTo(patient);assertThat(page.patient().maskedPhone()).isEqualTo("138****1234");
        assertThat(page.total()).isEqualTo(5);assertThat(page.items()).hasSize(5);
        assertThat(page.items()).extracting(HistoryVisit::doctorId).contains(doctor,secondDoctor);
        assertThat(page.items()).extracting(HistoryVisit::appointmentId).doesNotContain(crossPatient);
        assertThat(page.items().stream().filter(v->v.appointmentId()==completed).findFirst().orElseThrow().chiefComplaint()).isEqualTo("Historical completed complaint");
    }

    @Test void linkedDoctorCanReadCompletedVisitByAnotherDoctorWithPrescriptionAndHistory() {
        var detail=history.doctorDetail(doctorUser,anchor,completed);
        assertThat(detail.visit().doctorId()).isEqualTo(secondDoctor);
        assertThat(detail.medicalRecord().chiefComplaint()).isEqualTo("Historical completed complaint");
        assertThat(detail.prescription().prescription().status()).isEqualTo(PrescriptionStatus.ISSUED);
        assertThat(detail.prescription().items().getFirst().quantity()).isEqualTo(2);
        assertThat(detail.prescription().bill().totalAmount()).isEqualByComparingTo("6.60");
        assertThat(detail.appointmentHistory()).hasSize(1);assertThat(detail.encounterHistory()).hasSize(3);
    }

    @Test void unfinishedRecordsAndPrescriptionDraftsAreHiddenFromPatientAndEveryDoctorHistory() {
        for(HistoryDetail detail:List.of(history.patientDetail(owner,patient,draft),history.doctorDetail(doctorUser,anchor,draft),
                history.doctorDetail(secondDoctorUser,draft,draft))) {
            assertThat(detail.visit().encounterStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
            assertThat(detail.visit().chiefComplaint()).isNull();assertThat(detail.visit().diagnosis()).isNull();
            assertThat(detail.visit().prescriptionId()).isNull();assertThat(detail.visit().billStatus()).isNull();
            assertThat(detail.visit().totalAmount()).isNull();assertThat(detail.medicalRecord()).isNull();assertThat(detail.prescription()).isNull();
        }
        var ownDoctorPage=history.doctorPage(secondDoctorUser,draft,1,20);
        assertThat(ownDoctorPage.items().stream().filter(v->v.appointmentId()==draft).findFirst().orElseThrow().chiefComplaint()).isNull();
    }

    @Test void targetMustBelongToExactPatientAndAnchorMustBelongToCallingDoctor() {
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.patientDetail(owner,patient,crossPatient));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorDetail(doctorUser,anchor,crossPatient));
        fails(ErrorCode.PATIENT_NOT_FOUND,()->history.patientPage(otherOwner,patient,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(unrelatedDoctorUser,anchor,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(secondDoctorUser,anchor,1,20));
    }

    @Test void pendingAndCancelledUnattendedAppointmentsDoNotGrantDoctorAccess() {
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(doctorUser,pending,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorDetail(doctorUser,cancelled,completed));
        assertThat(history.doctorPage(doctorUser,anchor,1,20).patient().id()).isEqualTo(patient);
        db.update("UPDATE med_doctor SET status='DISABLED' WHERE id=?",doctor);
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(doctorUser,anchor,1,20));
    }

    @Test void pagesUseStableReverseVisitTimeOrderingAndRespectBounds() {
        var all=history.patientPage(owner,patient,1,50).items();
        assertThat(all).extracting(HistoryVisit::appointmentId).containsExactly(cancelled,pending,draft,anchor,completed);
        var one=history.patientPage(owner,patient,1,2);var two=history.patientPage(owner,patient,2,2);
        var three=history.patientPage(owner,patient,3,2);
        var joined=new ArrayList<Long>();for(var page:List.of(one,two,three)) joined.addAll(page.items().stream().map(HistoryVisit::appointmentId).toList());
        assertThat(joined).containsExactlyElementsOf(all.stream().map(HistoryVisit::appointmentId).toList());
        assertThat(history.patientPage(owner,patient,4,2).items()).isEmpty();
        fails(ErrorCode.VALIDATION_FAILED,()->history.patientPage(owner,patient,1,51));
    }

    @Test void issuedPrescriptionIsVisibleBeforeCompletionButDraftMedicalRecordRemainsHidden() {
        db.update("UPDATE med_prescription SET status='ISSUED',version=version+1,issued_at=NOW(3) WHERE id=?",draftRx);
        insert("INSERT INTO med_bill(prescription_id,status,total_amount) VALUES(?,'UNPAID',3.30)",draftRx);
        var detail=history.doctorDetail(doctorUser,anchor,draft);
        assertThat(detail.medicalRecord()).isNull();assertThat(detail.visit().chiefComplaint()).isNull();
        assertThat(detail.prescription()).isNotNull();assertThat(detail.visit().prescriptionId()).isEqualTo(draftRx);
        assertThat(detail.prescription().items().getFirst().lineAmount()).isEqualByComparingTo("3.30");
    }

    @Test void unboundDoctorCannotUseAnOtherwiseBookedAnchor() {
        db.update("UPDATE med_doctor SET user_id=9223372036854775806 WHERE id=?",doctor);
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(doctorUser,anchor,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorDetail(doctorUser,anchor,completed));
    }

    @Test void bookedStatusCannotBypassMismatchedEncounterDoctor() {
        // The BOOKED branch must not independently authorize a dirty encounter.
        db.update("UPDATE med_encounter SET doctor_id=? WHERE id=?",unrelatedDoctor,completedEncounter);
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(secondDoctorUser,completed,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorDetail(secondDoctorUser,completed,draft));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(unrelatedDoctorUser,completed,1,20));
    }

    @Test void scheduleReassignmentDoesNotTransferAnExistingEncountersHistoryAuthority() {
        db.update("""
                UPDATE med_schedule s JOIN med_slot sl ON sl.schedule_id=s.id JOIN med_appointment a ON a.slot_id=sl.id
                SET s.doctor_id=? WHERE a.id=?
                """,doctor,completed);
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(doctorUser,completed,1,20));
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->history.doctorPage(secondDoctorUser,completed,1,20));
        // A separate, internally consistent own anchor continues to permit
        // the explicitly allowed cross-doctor read-only history view.
        assertThat(history.doctorDetail(doctorUser,anchor,completed).medicalRecord()).isNotNull();
    }

    @Test void pageAndDetailParticipateInTheSameRepeatableReadSnapshot() {
        TransactionTemplate read=new TransactionTemplate(transactions);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setReadOnly(true);
        TransactionTemplate writer=new TransactionTemplate(transactions);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.executeWithoutResult(status->{
            var page=history.patientPage(owner,patient,1,20);
            assertThat(page.items().stream().filter(v->v.appointmentId()==draft).findFirst().orElseThrow().encounterStatus())
                    .isEqualTo(EncounterStatus.IN_PROGRESS);
            writer.executeWithoutResult(write->{
                db.update("UPDATE med_medical_record SET chief_complaint='Committed between page and detail' WHERE encounter_id=?",draftEncounter);
                db.update("UPDATE med_encounter SET status='COMPLETED',completed_at=NOW(3),version=version+1 WHERE id=?",draftEncounter);
            });
            var sameSnapshot=history.patientDetail(owner,patient,draft);
            assertThat(sameSnapshot.visit().encounterStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
            assertThat(sameSnapshot.medicalRecord()).isNull();
        });
        var freshSnapshot=history.patientDetail(owner,patient,draft);
        assertThat(freshSnapshot.visit().encounterStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(freshSnapshot.medicalRecord().chiefComplaint()).isEqualTo("Committed between page and detail");
    }

    private long user(String username,String role){return insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused',?)",username,role);}
    private long doctor(long user,String name){return insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?,?,?)",user,department,name);}
    private long patient(String name){return insert("INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship) VALUES(?,?,'UNKNOWN','2000-01-01','13800001234','SELF')",owner,name);}
    private long appointment(long patientId,long doctorId,int day,String start,String end,String status) {
        long schedule=insert("INSERT INTO med_schedule(doctor_id,clinic_room_id,work_date,start_time,end_time,status) VALUES(?,?,DATE_ADD(CURRENT_DATE(),INTERVAL ? DAY),?,?,'PUBLISHED')",doctorId,room,day,start,end);
        scheduleIds.add(schedule);
        long slot=insert("INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity) VALUES(?,DATE_ADD(CURRENT_DATE(),INTERVAL ? DAY),?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY),10,9)",schedule,day,start,end);
        long appointment=insert("INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline) VALUES(?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY),DATE_ADD(NOW(3),INTERVAL 7 DAY))",patientId,slot,status);
        insert("INSERT INTO med_appointment_status_log(appointment_id,from_status,to_status,event_type,actor_user_id) VALUES(?,NULL,'PENDING_CONFIRMATION','CREATED',?)",appointment,owner);
        return appointment;
    }
    private long encounter(long appointment,long doctorId,String status,String complaint) {
        long encounter=insert("""
                INSERT INTO med_encounter(appointment_id,doctor_id,status,checked_in_at,started_at,completed_at,version)
                SELECT a.id,?,?,TIMESTAMP(sl.work_date,sl.start_time)-INTERVAL 10 MINUTE,
                  TIMESTAMP(sl.work_date,sl.start_time),CASE WHEN ?='COMPLETED' THEN TIMESTAMP(sl.work_date,sl.start_time)+INTERVAL 15 MINUTE ELSE NULL END,
                  CASE WHEN ?='COMPLETED' THEN 2 ELSE 1 END
                FROM med_appointment a JOIN med_slot sl ON sl.id=a.slot_id WHERE a.id=?
                """,doctorId,status,status,status,appointment);
        insert("INSERT INTO med_medical_record(encounter_id,chief_complaint,present_illness,diagnosis,advice,version) VALUES(?,?,?,'History diagnosis','History advice',1)",encounter,complaint,"Present illness for "+complaint);
        insert("INSERT INTO med_encounter_status_log(encounter_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,NULL,'WAITING','CHECKED_IN',?,NOW(3))",encounter,owner);
        insert("INSERT INTO med_encounter_status_log(encounter_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,'WAITING','IN_PROGRESS','STARTED',?,NOW(3))",encounter,secondDoctorUser);
        if("COMPLETED".equals(status)) insert("INSERT INTO med_encounter_status_log(encounter_id,from_status,to_status,event_type,actor_user_id,created_at) VALUES(?,'IN_PROGRESS','COMPLETED','COMPLETED',?,NOW(3))",encounter,secondDoctorUser);
        return encounter;
    }
    private long prescription(long encounter,String status,int quantity) {
        long rx=insert("INSERT INTO med_prescription(encounter_id,status,total_amount,version,issued_at) VALUES(?,?,3.30*?,1,CASE WHEN ?='ISSUED' THEN NOW(3) ELSE NULL END)",encounter,status,quantity,status);
        insert("INSERT INTO med_prescription_item(prescription_id,drug_id,drug_code,drug_name,specification,unit,quantity,instructions,unit_price,line_amount) SELECT ?,id,code,name,specification,unit,?,'History instructions',unit_price,unit_price*? FROM med_drug WHERE id=?",rx,quantity,quantity,drug);
        if("ISSUED".equals(status)) insert("INSERT INTO med_bill(prescription_id,status,total_amount) VALUES(?,'UNPAID',3.30*?)",rx,quantity);
        return rx;
    }
    private long insert(String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();db.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return key.getKey().longValue();
    }
    private void fails(ErrorCode expected,Runnable action) {assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(expected));}
    private static String isolatedUrl() {
        String url=System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");var match=Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$").matcher(url==null?"":url);
        if(!match.matches() || Integer.parseInt(match.group(1))==3306 || Integer.parseInt(match.group(1))>65535)throw new IllegalStateException("Refusing normal database; use isolated tools/test-confirmation.ps1");return url;
    }
}
