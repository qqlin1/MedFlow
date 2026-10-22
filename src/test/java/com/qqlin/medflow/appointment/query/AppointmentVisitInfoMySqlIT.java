package com.qqlin.medflow.appointment.query;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Read-only batch joins/ownership against a disposable loopback schema, with rollback fixtures. */
@Tag("mysql-it")
@Execution(ExecutionMode.SAME_THREAD)
@Transactional
@SpringBootTest(properties={"medflow.demo.accounts.enabled=false","medflow.demo.workflow.enabled=false",
        "medflow.appointment.expiration.enabled=false","medflow.doctor-cache.enabled=false"})
class AppointmentVisitInfoMySqlIT {
    private static final String URL=isolatedUrl();
    @Autowired AppointmentVisitInfoMapper mapper;
    @Autowired JdbcTemplate db;
    long owner,otherOwner,patient,family,foreignPatient,doctor,room,department;
    String departmentName;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->URL);registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",()->"");
        registry.add("medflow.security.jwt.secret",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach void fixture() {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("medflow_confirmation_test");
        String suffix=UUID.randomUUID().toString().substring(0,12);
        owner=user("vs.owner."+suffix,"PATIENT");otherOwner=user("vs.other."+suffix,"PATIENT");
        long doctorUser=user("vs.doctor."+suffix,"DOCTOR");
        departmentName="Visit department "+suffix;
        department=insert("INSERT INTO med_department(name) VALUES(?)",departmentName);
        room=insert("INSERT INTO med_clinic_room(department_id,name) VALUES(?,'Visit room')",department);
        doctor=insert("INSERT INTO med_doctor(user_id,department_id,name) VALUES(?,?,'Visit doctor')",doctorUser,department);
        patient=patient(owner,"Visit patient");family=patient(owner,"Family patient");
        foreignPatient=patient(otherOwner,"PRIVATE other patient");
    }

    @Test void batchReturnsOwnedFamilyAppointmentsInStableOrderAndOmitsForeignAndUnknownIds() {
        LocalDate tomorrow=today().plusDays(1);
        long first=appointment(patient,tomorrow,LocalTime.of(8,0),LocalTime.of(8,30),"BOOKED");
        long second=appointment(family,tomorrow,LocalTime.of(9,0),LocalTime.of(9,30),"PENDING_CONFIRMATION");
        long foreign=appointment(foreignPatient,tomorrow,LocalTime.of(10,0),LocalTime.of(10,30),"BOOKED");
        var result=mapper.findOwnedBatch(List.of(foreign,second,Long.MAX_VALUE,first,first),owner);
        assertThat(result).extracting(AppointmentVisitInfo::appointmentId).containsExactly(first,second);
        assertThat(result).extracting(AppointmentVisitInfo::patientName).containsExactly("Visit patient","Family patient");
        assertThat(mapper.findOwnedBatch(List.of(first,second),otherOwner)).isEmpty();
    }

    @Test void joinsSupplyDateDoctorDepartmentRoomDeadlinesAndEncounterWithoutMedicalBody() {
        LocalDate yesterday=today().minusDays(1);
        long id=appointment(patient,yesterday,LocalTime.of(8,0),LocalTime.of(8,30),"BOOKED");
        long encounter=insert("INSERT INTO med_encounter(appointment_id,doctor_id,status,checked_in_at,started_at,completed_at) VALUES(?,?,'COMPLETED',?,?,?)",
                id,doctor,yesterday.atTime(7,50),yesterday.atTime(8,0),yesterday.atTime(8,15));
        var info=mapper.findOwnedBatch(List.of(id),owner).getFirst();
        assertThat(info.appointmentId()).isEqualTo(id);assertThat(info.patientId()).isEqualTo(patient);
        assertThat(info.workDate()).isEqualTo(yesterday);assertThat(info.startTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(info.endTime()).isEqualTo(LocalTime.of(8,30));assertThat(info.doctorName()).isEqualTo("Visit doctor");
        assertThat(info.departmentName()).isEqualTo(departmentName);assertThat(info.clinicRoomName()).isEqualTo("Visit room");
        assertThat(info.status()).isEqualTo("BOOKED");assertThat(info.confirmDeadline()).isNotNull();
        assertThat(info.cancelDeadline()).isNotNull();assertThat(info.encounterId()).isEqualTo(encounter);
        assertThat(info.encounterStatus()).isEqualTo("COMPLETED");assertThat(info.canCheckIn()).isFalse();
    }

    @Test void checkInAvailabilityUsesDatabaseTimeAndBecomesFalseAfterAttendanceExists() {
        LocalDateTime now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",LocalDateTime.class);
        // Avoid constructing a slot across midnight; ordinary clinic fixtures remain within one day.
        Assumptions.assumeTrue(now.getHour()>0 && now.getHour()<23);
        LocalTime start=now.toLocalTime().minusMinutes(5).withNano(0);
        LocalTime end=now.toLocalTime().plusMinutes(10).withNano(0);
        long id=appointment(patient,now.toLocalDate(),start,end,"BOOKED");
        assertThat(mapper.findOwnedBatch(List.of(id),owner).getFirst().canCheckIn()).isTrue();
        insert("INSERT INTO med_encounter(appointment_id,doctor_id,status,checked_in_at) VALUES(?,?,'WAITING',?)",id,doctor,now);
        assertThat(mapper.findOwnedBatch(List.of(id),owner).getFirst().canCheckIn()).isFalse();
    }

    private LocalDate today(){return db.queryForObject("SELECT CURRENT_DATE",LocalDate.class);}
    private long user(String username,String role){return insert("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'unused',?)",username,role);}
    private long patient(long ownerId,String name){return insert("INSERT INTO med_patient(owner_user_id,name,gender,birth_date,phone,relationship) VALUES(?,?,'UNKNOWN','2000-01-01','13800001234','SELF')",ownerId,name);}
    private long appointment(long patientId,LocalDate date,LocalTime start,LocalTime end,String status) {
        long schedule=insert("INSERT INTO med_schedule(doctor_id,clinic_room_id,work_date,start_time,end_time,status) VALUES(?,?,?,?,?,'PUBLISHED')",doctor,room,date,start,end);
        long slot=insert("INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity) VALUES(?,?,?,?,?,10,9)",schedule,date,start,end,date.atTime(start).minusMinutes(10));
        return insert("INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline) VALUES(?,?,?,?,?)",patientId,slot,status,date.atTime(start).minusMinutes(5),date.atTime(start).minusMinutes(10));
    }
    private long insert(String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();db.update(connection->{
            PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;
        },key);return key.getKey().longValue();
    }
    private static String isolatedUrl() {
        String url=System.getenv("MEDFLOW_CONFIRMATION_TEST_URL");
        var match=Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([1-9]\\d{0,4})/medflow_confirmation_test(?:\\?[^\\s]*)?$").matcher(url==null?"":url);
        if(!match.matches() || Integer.parseInt(match.group(1))==3306 || Integer.parseInt(match.group(1))>65535)
            throw new IllegalStateException("Refusing normal database; use isolated tools/test-confirmation.ps1");
        return url;
    }
}
