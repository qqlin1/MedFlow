package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.billing.domain.PrescriptionStatus;
import com.qqlin.medflow.billing.dto.PrescriptionSummary;
import com.qqlin.medflow.billing.mapper.BillingMapper;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.MedicalRecordResponse;
import com.qqlin.medflow.patient.domain.*;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientHistoryServiceTest {
    static final long USER=10,PATIENT=20,ANCHOR=30,TARGET=40,ENCOUNTER=50,RX=60;
    @Mock PatientHistoryMapper mapper;
    @Mock BillingMapper billing;
    PatientHistoryService service;

    @BeforeEach void setup(){service=new PatientHistoryService(mapper,billing);}

    @Test void unownedPatientIsHiddenBeforeReadingHistory() {
        fails(ErrorCode.PATIENT_NOT_FOUND,()->service.patientPage(USER,PATIENT,1,20));
        verify(mapper,never()).count(anyLong());verifyNoInteractions(billing);
    }
    @Test void doctorWithoutAuthorizedAnchorCannotProbeAnyPatientHistory() {
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->service.doctorPage(USER,ANCHOR,1,20));
        verify(mapper,never()).count(anyLong());verify(mapper,never()).findPage(anyLong(),anyLong(),anyInt());
    }
    @Test void patientContactIsMaskedAndPaginationUsesStableBoundedOffset() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.count(PATIENT)).thenReturn(3L);
        when(mapper.findPage(PATIENT,2,2)).thenReturn(List.of(visit(EncounterStatus.COMPLETED,null)));
        var page=service.patientPage(USER,PATIENT,2,2);
        assertThat(page.patient().maskedPhone()).isEqualTo("138****1234");
        assertThat(page.patient().toString()).doesNotContain("13800001234");
        assertThat(page.total()).isEqualTo(3);assertThat(page.items()).hasSize(1);
        verify(mapper).findPage(PATIENT,2,2);
    }
    @Test void oversizedOrInvalidPageIsRejectedBeforeOwnershipQuery() {
        fails(ErrorCode.VALIDATION_FAILED,()->service.patientPage(USER,PATIENT,1,51));
        fails(ErrorCode.VALIDATION_FAILED,()->service.doctorPage(USER,ANCHOR,0,20));
        verifyNoInteractions(mapper,billing);
    }
    @Test void pageBeyondTotalReturnsEmptyWithoutIssuingAnUnnecessaryPageRead() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.count(PATIENT)).thenReturn(1L);
        assertThat(service.patientPage(USER,PATIENT,Integer.MAX_VALUE,50).items()).isEmpty();
        verify(mapper,never()).findPage(anyLong(),anyLong(),anyInt());
    }
    @Test void authorizedDoctorSeesSamePatientsHistoricalVisitsAcrossDoctors() {
        when(mapper.findDoctorAnchorPatient(USER,ANCHOR)).thenReturn(patient());
        when(mapper.count(PATIENT)).thenReturn(1L);
        when(mapper.findPage(PATIENT,0,20)).thenReturn(List.of(visit(EncounterStatus.COMPLETED,null)));
        assertThat(service.doctorPage(USER,ANCHOR,1,20).items().getFirst().doctorId()).isEqualTo(999);
    }
    @Test void detailCannotCrossFromAnchoredPatientToAnotherPatient() {
        when(mapper.findDoctorAnchorPatient(USER,ANCHOR)).thenReturn(patient());
        fails(ErrorCode.APPOINTMENT_NOT_FOUND,()->service.doctorDetail(USER,ANCHOR,TARGET));
        verify(mapper).findVisit(PATIENT,TARGET);verifyNoInteractions(billing);
    }
    @Test void unfinishedMedicalRecordIsNeverFetchedEvenThroughDoctorsHistory() {
        when(mapper.findDoctorAnchorPatient(USER,ANCHOR)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(visit(EncounterStatus.IN_PROGRESS,null));
        var result=service.doctorDetail(USER,ANCHOR,TARGET);
        assertThat(result.medicalRecord()).isNull();assertThat(result.prescription()).isNull();
        verify(mapper,never()).findCompletedMedicalRecord(anyLong());verifyNoInteractions(billing);
    }
    @Test void completedRecordUsesReadOnlyHistoryPathWithoutCheckingTreatingDoctorIdentity() {
        when(mapper.findDoctorAnchorPatient(USER,ANCHOR)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(visit(EncounterStatus.COMPLETED,null));
        var record=new MedicalRecordResponse(1,ENCOUNTER,"历史主诉","历史病史","历史诊断","历史医嘱",1,LocalDateTime.now());
        when(mapper.findCompletedMedicalRecord(ENCOUNTER)).thenReturn(record);
        assertThat(service.doctorDetail(USER,ANCHOR,TARGET).medicalRecord()).isEqualTo(record);
        verifyNoInteractions(billing);
    }
    @Test void issuedPrescriptionCanBeViewedBeforeEncounterCompletesWhileRecordStaysHidden() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(visit(EncounterStatus.IN_PROGRESS,RX));
        when(billing.findSummary(RX)).thenReturn(summary(PrescriptionStatus.ISSUED,ENCOUNTER));
        var detail=service.patientDetail(USER,PATIENT,TARGET);
        assertThat(detail.medicalRecord()).isNull();assertThat(detail.prescription()).isNotNull();
        verify(mapper,never()).findCompletedMedicalRecord(anyLong());
    }
    @Test void defenseInDepthRejectsDraftPrescriptionEvenIfVisitProjectionContainsItsId() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(visit(EncounterStatus.IN_PROGRESS,RX));
        when(billing.findSummary(RX)).thenReturn(summary(PrescriptionStatus.DRAFT,ENCOUNTER));
        assertThat(service.patientDetail(USER,PATIENT,TARGET).prescription()).isNull();
        verify(billing,never()).findItems(anyLong());verify(billing,never()).findBill(anyLong());
    }
    @Test void prescriptionMustBelongToTheExactTargetEncounter() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(visit(EncounterStatus.IN_PROGRESS,RX));
        when(billing.findSummary(RX)).thenReturn(summary(PrescriptionStatus.ISSUED,ENCOUNTER+1));
        assertThat(service.patientDetail(USER,PATIENT,TARGET).prescription()).isNull();
        verify(billing,never()).findItems(anyLong());
    }
    @Test void appointmentWithoutEncounterHasEmptyEncounterHistory() {
        when(mapper.findOwnedPatient(USER,PATIENT)).thenReturn(patient());
        when(mapper.findVisit(PATIENT,TARGET)).thenReturn(new HistoryVisit(TARGET,LocalDate.now(),LocalTime.NOON,
                LocalTime.of(12,30),999,"历史医生","科室","诊室",AppointmentStatus.CANCELLED,null,null,null,null,null,null,null));
        var detail=service.patientDetail(USER,PATIENT,TARGET);
        assertThat(detail.encounterHistory()).isEmpty();assertThat(detail.medicalRecord()).isNull();
        verify(mapper,never()).findEncounterHistory(anyLong());
    }
    private Patient patient(){return new Patient(PATIENT,USER,"同一就诊人",PatientGender.UNKNOWN,LocalDate.of(2000,1,1),"13800001234","SELF");}
    private HistoryVisit visit(EncounterStatus status,Long prescription) {
        return new HistoryVisit(TARGET,LocalDate.now().minusDays(1),LocalTime.of(8,0),LocalTime.of(8,30),999,"历史医生",
                "科室","诊室",AppointmentStatus.BOOKED,ENCOUNTER,status,status==EncounterStatus.COMPLETED?"历史主诉":null,
                status==EncounterStatus.COMPLETED?"历史诊断":null,prescription,null,prescription==null?null:new BigDecimal("6.60"));
    }
    private PrescriptionSummary summary(PrescriptionStatus status,long encounter) {
        return new PrescriptionSummary(RX,encounter,TARGET,"同一就诊人","历史医生","科室",status,new BigDecimal("6.60"),2,LocalDateTime.now(),status==PrescriptionStatus.ISSUED?LocalDateTime.now():null);
    }
    private void fails(ErrorCode expected,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
