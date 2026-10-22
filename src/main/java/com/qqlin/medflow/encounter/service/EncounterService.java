package com.qqlin.medflow.encounter.service;

import com.qqlin.medflow.encounter.domain.CheckInAppointment;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.*;
import com.qqlin.medflow.encounter.mapper.EncounterMapper;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.qqlin.medflow.encounter.domain.EncounterStatus.*;

@Service
public class EncounterService {
    private final EncounterMapper mapper;

    public EncounterService(EncounterMapper mapper) {
        this.mapper = mapper;
    }

    /** Same Slot -> Appointment order as cancellation; no capacity is changed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterDetailResponse checkIn(long userId, long appointmentId) {
        positive(appointmentId);
        Long slotId = mapper.findOwnedAppointmentSlot(appointmentId, userId);
        if (slotId == null || mapper.lockSlot(slotId) == null) {
            throw error(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        CheckInAppointment appointment = mapper.lockOwnedAppointment(appointmentId, userId);
        if (appointment == null) throw error(ErrorCode.APPOINTMENT_NOT_FOUND);
        // The appointment lock serializes both duplicate check-in and cancellation.
        if (mapper.findEncounterIdByAppointment(appointmentId) != null) {
            return patientDetail(userId, appointmentId);
        }
        // A timestamp captured before either lock may be stale after a long wait.
        LocalDateTime now = mapper.databaseNow();
        LocalDateTime start = appointment.workDate().atTime(appointment.startTime());
        LocalDateTime end = appointment.workDate().atTime(appointment.endTime());
        if (!"BOOKED".equals(appointment.status())
                || !now.toLocalDate().equals(appointment.workDate())
                || now.isBefore(start.minusMinutes(30)) || !now.isBefore(end)) {
            throw error(ErrorCode.CHECK_IN_NOT_ALLOWED);
        }
        mapper.insertEncounter(appointmentId, appointment.doctorId(), now);
        Long id = mapper.findEncounterIdByAppointment(appointmentId);
        if (id == null) throw new IllegalStateException("Inserted encounter was not found");
        mapper.insertHistory(id, null, WAITING, "CHECKED_IN", userId, now);
        return patientDetail(userId, appointmentId);
    }

    @Transactional(readOnly = true)
    public EncounterDetailResponse patientDetail(long userId, long appointmentId) {
        positive(appointmentId);
        EncounterSummary encounter = mapper.findPatientEncounter(appointmentId, userId);
        if (encounter == null) throw error(ErrorCode.ENCOUNTER_NOT_FOUND);
        // Even an owned appointment does not grant access to the doctor's draft.
        return detail(encounter, encounter.status() == COMPLETED);
    }

    @Transactional(readOnly = true)
    public EncounterPageResponse doctorQueue(long userId, LocalDate workDate, EncounterStatus status, int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw error(ErrorCode.VALIDATION_FAILED);
        long doctorId = requireDoctor(userId, false);
        LocalDate date = workDate == null ? mapper.databaseNow().toLocalDate() : workDate;
        return new EncounterPageResponse(
                mapper.listDoctorEncounters(doctorId, date, status, ((long) page - 1) * size, size),
                mapper.countDoctorEncounters(doctorId, date, status), page, size);
    }

    @Transactional(readOnly = true)
    public EncounterDetailResponse doctorDetail(long userId, long encounterId) {
        positive(encounterId);
        EncounterSummary encounter = mapper.findDoctorEncounter(encounterId, requireDoctor(userId, false));
        if (encounter == null) throw error(ErrorCode.ENCOUNTER_NOT_FOUND);
        return detail(encounter, true);
    }

    /** The doctor row is the serialization point across different encounters. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterDetailResponse start(long userId, long encounterId) {
        positive(encounterId);
        long doctorId = requireDoctor(userId, true);
        EncounterSummary encounter = locked(encounterId, doctorId);
        if (encounter.status() == IN_PROGRESS) return detail(encounter, true);
        requireStatus(encounter, WAITING);
        LocalDateTime now = mapper.databaseNow();
        if (now.isBefore(encounter.workDate().atTime(encounter.startTime()))) {
            throw error(ErrorCode.ENCOUNTER_STATE_CONFLICT);
        }
        if (mapper.findAttendingEncounter(doctorId) != null) throw error(ErrorCode.DOCTOR_ALREADY_ATTENDING);
        try {
            transition(encounter, IN_PROGRESS, "STARTED", userId, now);
        } catch (DuplicateKeyException exception) {
            // The generated unique key is a second line of protection.
            throw error(ErrorCode.DOCTOR_ALREADY_ATTENDING);
        }
        return doctorDetail(userId, encounterId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterDetailResponse skip(long userId, long encounterId) {
        positive(encounterId);
        EncounterSummary encounter = locked(encounterId, requireDoctor(userId, false));
        if (encounter.status() == SKIPPED) return detail(encounter, true);
        requireStatus(encounter, WAITING);
        transition(encounter, SKIPPED, "SKIPPED", userId, mapper.databaseNow());
        return doctorDetail(userId, encounterId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterDetailResponse saveMedicalRecord(long userId, long encounterId, SaveMedicalRecordRequest request) {
        positive(encounterId);
        SaveMedicalRecordRequest normalized = normalize(request);
        EncounterSummary encounter = locked(encounterId, requireDoctor(userId, false));
        requireStatus(encounter, IN_PROGRESS);
        MedicalRecordResponse record = mapper.findMedicalRecord(encounterId);
        long currentVersion = record == null ? 0 : record.version();
        if (normalized.version() != currentVersion) throw error(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT);
        LocalDateTime now = mapper.databaseNow();
        if (record == null) {
            mapper.insertMedicalRecord(encounterId, normalized, now);
        } else if (mapper.updateMedicalRecord(encounterId, normalized, now) != 1) {
            throw error(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT);
        }
        return detail(encounter, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterDetailResponse complete(long userId, long encounterId, long recordVersion) {
        positive(encounterId);
        if (recordVersion < 0) throw error(ErrorCode.VALIDATION_FAILED);
        EncounterSummary encounter = locked(encounterId, requireDoctor(userId, false));
        if (encounter.status() == COMPLETED) return detail(encounter, true);
        requireStatus(encounter, IN_PROGRESS);
        if ("DRAFT".equals(mapper.lockPrescriptionStatus(encounterId))) {
            throw error(ErrorCode.PRESCRIPTION_DRAFT_EXISTS);
        }
        MedicalRecordResponse record = mapper.findMedicalRecord(encounterId);
        if (record == null || record.chiefComplaint().isBlank() || record.diagnosis().isBlank()) {
            throw error(ErrorCode.MEDICAL_RECORD_INCOMPLETE);
        }
        if (record.version() != recordVersion) throw error(ErrorCode.MEDICAL_RECORD_VERSION_CONFLICT);
        transition(encounter, COMPLETED, "COMPLETED", userId, mapper.databaseNow());
        return doctorDetail(userId, encounterId);
    }

    private EncounterDetailResponse detail(EncounterSummary encounter, boolean showRecord) {
        return new EncounterDetailResponse(encounter,
                showRecord ? mapper.findMedicalRecord(encounter.id()) : null, mapper.findHistory(encounter.id()));
    }

    private EncounterSummary locked(long encounterId, long doctorId) {
        EncounterSummary encounter = mapper.lockDoctorEncounter(encounterId, doctorId);
        if (encounter == null) throw error(ErrorCode.ENCOUNTER_NOT_FOUND);
        return encounter;
    }

    private long requireDoctor(long userId, boolean lock) {
        Long id = lock ? mapper.lockDoctorId(userId) : mapper.findDoctorId(userId);
        if (id == null) throw error(ErrorCode.DOCTOR_NOT_FOUND);
        return id;
    }

    private void transition(EncounterSummary encounter, EncounterStatus next, String event, long userId, LocalDateTime now) {
        if (mapper.transition(encounter.id(), encounter.status(), next, now) != 1) {
            throw error(ErrorCode.ENCOUNTER_STATE_CONFLICT);
        }
        mapper.insertHistory(encounter.id(), encounter.status(), next, event, userId, now);
    }

    private static SaveMedicalRecordRequest normalize(SaveMedicalRecordRequest request) {
        if (request == null || request.version() == null || request.version() < 0) throw error(ErrorCode.VALIDATION_FAILED);
        return new SaveMedicalRecordRequest(text(request.chiefComplaint(), 2000), text(request.presentIllness(), 8000),
                text(request.diagnosis(), 4000), text(request.advice(), 8000), request.version());
    }

    private static String text(String value, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maxLength) throw error(ErrorCode.VALIDATION_FAILED);
        return normalized;
    }

    private static void requireStatus(EncounterSummary encounter, EncounterStatus status) {
        if (encounter.status() != status) throw error(ErrorCode.ENCOUNTER_STATE_CONFLICT);
    }

    private static void positive(long id) {
        if (id <= 0) throw error(ErrorCode.VALIDATION_FAILED);
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
