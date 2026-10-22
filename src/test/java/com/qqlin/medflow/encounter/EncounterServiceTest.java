package com.qqlin.medflow.encounter;

import com.qqlin.medflow.encounter.domain.CheckInAppointment;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.*;
import com.qqlin.medflow.encounter.mapper.EncounterMapper;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.qqlin.medflow.encounter.domain.EncounterStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Rule tests; the separate MySQL test proves actual locks and rollback. */
@ExtendWith(MockitoExtension.class)
class EncounterServiceTest {
    static final long USER = 10, DOCTOR = 20, APPOINTMENT = 30, ENCOUNTER = 40, SLOT = 50;
    static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    static final LocalDateTime NOW = DAY.atTime(9, 0);
    @Mock EncounterMapper mapper;
    EncounterService service;

    @BeforeEach void setup() { service = new EncounterService(mapper); }

    @Test void unownedCheckInReturns404BeforeAnyLocks() {
        when(mapper.findOwnedAppointmentSlot(APPOINTMENT, USER)).thenReturn(null);
        fails(ErrorCode.APPOINTMENT_NOT_FOUND, () -> service.checkIn(USER, APPOINTMENT));
        verify(mapper, never()).lockSlot(anyLong());
    }

    @Test void checkInLocksSlotThenAppointmentThenReadsDatabaseTime() {
        checkInFixture("BOOKED", NOW);
        when(mapper.findEncounterIdByAppointment(APPOINTMENT)).thenReturn(null, ENCOUNTER);
        when(mapper.findPatientEncounter(APPOINTMENT, USER)).thenReturn(summary(WAITING));
        assertThat(service.checkIn(USER, APPOINTMENT).medicalRecord()).isNull();
        var order = inOrder(mapper);
        order.verify(mapper).findOwnedAppointmentSlot(APPOINTMENT, USER);
        order.verify(mapper).lockSlot(SLOT);
        order.verify(mapper).lockOwnedAppointment(APPOINTMENT, USER);
        order.verify(mapper).findEncounterIdByAppointment(APPOINTMENT);
        order.verify(mapper).databaseNow();
        verify(mapper).insertHistory(ENCOUNTER, null, WAITING, "CHECKED_IN", USER, NOW);
    }

    @Test void repeatCheckInReturnsOriginalEvenAfterWindowEnds() {
        when(mapper.findOwnedAppointmentSlot(APPOINTMENT, USER)).thenReturn(SLOT);
        when(mapper.lockSlot(SLOT)).thenReturn(SLOT);
        when(mapper.lockOwnedAppointment(APPOINTMENT, USER)).thenReturn(appointment("BOOKED"));
        when(mapper.findEncounterIdByAppointment(APPOINTMENT)).thenReturn(ENCOUNTER);
        when(mapper.findPatientEncounter(APPOINTMENT, USER)).thenReturn(summary(COMPLETED));
        service.checkIn(USER, APPOINTMENT);
        verify(mapper, never()).databaseNow();
        verify(mapper, never()).insertEncounter(anyLong(), anyLong(), any());
    }

    @Test void unconfirmedAppointmentCannotCheckIn() {
        checkInFixture("PENDING_CONFIRMATION", NOW);
        fails(ErrorCode.CHECK_IN_NOT_ALLOWED, () -> service.checkIn(USER, APPOINTMENT));
    }

    @Test void checkInCannotCrossIntoPreviousCalendarDay() {
        checkInFixture("BOOKED", DAY.minusDays(1).atTime(23, 59));
        fails(ErrorCode.CHECK_IN_NOT_ALLOWED, () -> service.checkIn(USER, APPOINTMENT));
    }

    @Test void checkInIsRejectedBeforeThirtyMinuteWindow() {
        checkInFixture("BOOKED", DAY.atTime(8, 29, 59));
        fails(ErrorCode.CHECK_IN_NOT_ALLOWED, () -> service.checkIn(USER, APPOINTMENT));
    }

    @Test void checkInEndIsExclusive() {
        checkInFixture("BOOKED", DAY.atTime(9, 30));
        fails(ErrorCode.CHECK_IN_NOT_ALLOWED, () -> service.checkIn(USER, APPOINTMENT));
    }

    @Test void patientCannotReadDraftMedicalRecord() {
        when(mapper.findPatientEncounter(APPOINTMENT, USER)).thenReturn(summary(IN_PROGRESS));
        assertThat(service.patientDetail(USER, APPOINTMENT).medicalRecord()).isNull();
        verify(mapper, never()).findMedicalRecord(anyLong());
    }

    @Test void patientCanReadCompletedMedicalRecord() {
        when(mapper.findPatientEncounter(APPOINTMENT, USER)).thenReturn(summary(COMPLETED));
        when(mapper.findMedicalRecord(ENCOUNTER)).thenReturn(record(2));
        assertThat(service.patientDetail(USER, APPOINTMENT).medicalRecord().version()).isEqualTo(2);
    }

    @Test void doctorCannotOpenOtherDoctorsEncounter() {
        when(mapper.findDoctorId(USER)).thenReturn(DOCTOR);
        fails(ErrorCode.ENCOUNTER_NOT_FOUND, () -> service.doctorDetail(USER, ENCOUNTER));
        verify(mapper, never()).findMedicalRecord(anyLong());
    }

    @Test void doctorCannotStartBeforeSlotBegins() {
        when(mapper.lockDoctorId(USER)).thenReturn(DOCTOR);
        when(mapper.lockDoctorEncounter(ENCOUNTER, DOCTOR)).thenReturn(summary(WAITING));
        when(mapper.databaseNow()).thenReturn(NOW.minusSeconds(1));
        fails(ErrorCode.ENCOUNTER_STATE_CONFLICT, () -> service.start(USER, ENCOUNTER));
        verify(mapper, never()).transition(anyLong(), any(), any(), any());
    }

    @Test void doctorWithActiveEncounterCannotStartAnother() {
        when(mapper.lockDoctorId(USER)).thenReturn(DOCTOR);
        when(mapper.lockDoctorEncounter(ENCOUNTER, DOCTOR)).thenReturn(summary(WAITING));
        when(mapper.databaseNow()).thenReturn(NOW);
        when(mapper.findAttendingEncounter(DOCTOR)).thenReturn(999L);
        fails(ErrorCode.DOCTOR_ALREADY_ATTENDING, () -> service.start(USER, ENCOUNTER));
    }

    @Test void oldRecordVersionCannotOverwriteDraft() {
        doctorLocked(IN_PROGRESS);
        when(mapper.findMedicalRecord(ENCOUNTER)).thenReturn(record(2));
        fails(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT,
                () -> service.saveMedicalRecord(USER, ENCOUNTER, request(1)));
        verify(mapper, never()).updateMedicalRecord(anyLong(), any(), any());
    }

    @Test void firstSaveRequiresInitialVersionZero() {
        doctorLocked(IN_PROGRESS);
        fails(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT,
                () -> service.saveMedicalRecord(USER, ENCOUNTER, request(1)));
    }

    @Test void firstSaveCreatesVersionOneAndDoesNotAddStateHistory() {
        doctorLocked(IN_PROGRESS);
        when(mapper.databaseNow()).thenReturn(NOW);
        when(mapper.findMedicalRecord(ENCOUNTER)).thenReturn(null, record(1));
        assertThat(service.saveMedicalRecord(USER, ENCOUNTER, request(0)).medicalRecord().version()).isEqualTo(1);
        verify(mapper).insertMedicalRecord(ENCOUNTER, request(0), NOW);
        verify(mapper, never()).insertHistory(anyLong(), any(), any(), anyString(), anyLong(), any());
    }

    @Test void completedRecordCannotBeSavedAgain() {
        doctorLocked(COMPLETED);
        fails(ErrorCode.ENCOUNTER_STATE_CONFLICT, () -> service.saveMedicalRecord(USER, ENCOUNTER, request(1)));
        verify(mapper, never()).findMedicalRecord(anyLong());
    }

    @Test void completingWithoutSavedRecordIsRejected() {
        doctorLocked(IN_PROGRESS);
        fails(ErrorCode.MEDICAL_RECORD_INCOMPLETE, () -> service.complete(USER, ENCOUNTER, 0));
    }

    @Test void unissuedPrescriptionDraftBlocksCompletionBeforeMedicalRecordRead() {
        doctorLocked(IN_PROGRESS);
        when(mapper.lockPrescriptionStatus(ENCOUNTER)).thenReturn("DRAFT");
        fails(ErrorCode.PRESCRIPTION_DRAFT_EXISTS, () -> service.complete(USER, ENCOUNTER, 1));
        verify(mapper, never()).findMedicalRecord(anyLong());
        verify(mapper, never()).transition(anyLong(), any(), any(), any());
    }

    @Test void completingRequiresCurrentMedicalRecordVersionRatherThanEncounterVersion() {
        doctorLocked(IN_PROGRESS);
        when(mapper.findMedicalRecord(ENCOUNTER)).thenReturn(record(7));
        fails(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT, () -> service.complete(USER, ENCOUNTER, 0));
        verify(mapper, never()).transition(anyLong(), any(), any(), any());
    }

    @Test void completeWritesHistoryAndStateWithSameDatabaseTimestamp() {
        doctorLocked(IN_PROGRESS);
        when(mapper.findMedicalRecord(ENCOUNTER)).thenReturn(record(7));
        when(mapper.databaseNow()).thenReturn(NOW);
        when(mapper.transition(ENCOUNTER, IN_PROGRESS, COMPLETED, NOW)).thenReturn(1);
        when(mapper.findDoctorEncounter(ENCOUNTER, DOCTOR)).thenReturn(summary(COMPLETED));
        assertThat(service.complete(USER, ENCOUNTER, 7).encounter().status()).isEqualTo(COMPLETED);
        verify(mapper).insertHistory(ENCOUNTER, IN_PROGRESS, COMPLETED, "COMPLETED", USER, NOW);
    }

    @Test void repeatCompletionReturnsOriginalWithoutNewHistory() {
        doctorLocked(COMPLETED);
        service.complete(USER, ENCOUNTER, 1);
        verify(mapper, never()).transition(anyLong(), any(), any(), any());
        verify(mapper, never()).insertHistory(anyLong(), any(), any(), anyString(), anyLong(), any());
    }

    @Test void doctorCannotSkipInProgressEncounter() {
        doctorLocked(IN_PROGRESS);
        fails(ErrorCode.ENCOUNTER_STATE_CONFLICT, () -> service.skip(USER, ENCOUNTER));
    }

    @Test void queueRejectsUnboundedPageSize() {
        fails(ErrorCode.VALIDATION_FAILED, () -> service.doctorQueue(USER, DAY, null, 1, 51));
        verifyNoInteractions(mapper);
    }

    private void doctorLocked(EncounterStatus status) {
        when(mapper.findDoctorId(USER)).thenReturn(DOCTOR);
        when(mapper.lockDoctorEncounter(ENCOUNTER, DOCTOR)).thenReturn(summary(status));
    }

    private void checkInFixture(String status, LocalDateTime now) {
        when(mapper.findOwnedAppointmentSlot(APPOINTMENT, USER)).thenReturn(SLOT);
        when(mapper.lockSlot(SLOT)).thenReturn(SLOT);
        when(mapper.lockOwnedAppointment(APPOINTMENT, USER)).thenReturn(appointment(status));
        when(mapper.findEncounterIdByAppointment(APPOINTMENT)).thenReturn(null);
        when(mapper.databaseNow()).thenReturn(now);
    }

    private CheckInAppointment appointment(String status) {
        return new CheckInAppointment(APPOINTMENT, SLOT, status, DOCTOR, DAY, LocalTime.of(9, 0), LocalTime.of(9, 30));
    }

    private EncounterSummary summary(EncounterStatus status) {
        return new EncounterSummary(ENCOUNTER, APPOINTMENT, 60, "测试患者", DOCTOR, "测试医生", "内科", "一诊室",
                DAY, LocalTime.of(9, 0), LocalTime.of(9, 30), status, NOW, null, null, 0);
    }

    private MedicalRecordResponse record(long version) {
        return new MedicalRecordResponse(70, ENCOUNTER, "发热", "一天", "上呼吸道感染", "复诊", version, NOW);
    }

    private SaveMedicalRecordRequest request(long version) {
        return new SaveMedicalRecordRequest("发热", "一天", "上呼吸道感染", "复诊", version);
    }

    private void fails(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
