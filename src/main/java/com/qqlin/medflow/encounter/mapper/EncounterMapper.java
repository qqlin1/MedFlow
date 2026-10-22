package com.qqlin.medflow.encounter.mapper;

import com.qqlin.medflow.encounter.domain.CheckInAppointment;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EncounterMapper {
    Long findOwnedAppointmentSlot(@Param("appointmentId") long appointmentId, @Param("userId") long userId);
    Long lockSlot(@Param("slotId") long slotId);
    CheckInAppointment lockOwnedAppointment(@Param("appointmentId") long appointmentId, @Param("userId") long userId);
    LocalDateTime databaseNow();
    Long findEncounterIdByAppointment(@Param("appointmentId") long appointmentId);
    Long findDoctorId(@Param("userId") long userId);
    Long lockDoctorId(@Param("userId") long userId);
    EncounterSummary lockDoctorEncounter(@Param("encounterId") long encounterId, @Param("doctorId") long doctorId);
    EncounterSummary findDoctorEncounter(@Param("encounterId") long encounterId, @Param("doctorId") long doctorId);
    EncounterSummary findPatientEncounter(@Param("appointmentId") long appointmentId, @Param("userId") long userId);
    Long findAttendingEncounter(@Param("doctorId") long doctorId);
    int insertEncounter(@Param("appointmentId") long appointmentId, @Param("doctorId") long doctorId, @Param("now") LocalDateTime now);
    int transition(@Param("encounterId") long encounterId, @Param("fromStatus") EncounterStatus fromStatus,
            @Param("toStatus") EncounterStatus toStatus, @Param("now") LocalDateTime now);
    int insertHistory(@Param("encounterId") long encounterId, @Param("fromStatus") EncounterStatus fromStatus,
            @Param("toStatus") EncounterStatus toStatus, @Param("eventType") String eventType,
            @Param("userId") long userId, @Param("now") LocalDateTime now);
    MedicalRecordResponse findMedicalRecord(@Param("encounterId") long encounterId);
    int insertMedicalRecord(@Param("encounterId") long encounterId, @Param("record") SaveMedicalRecordRequest record,
            @Param("now") LocalDateTime now);
    int updateMedicalRecord(@Param("encounterId") long encounterId, @Param("record") SaveMedicalRecordRequest record,
            @Param("now") LocalDateTime now);
    List<EncounterHistoryResponse> findHistory(@Param("encounterId") long encounterId);
    List<EncounterSummary> listDoctorEncounters(@Param("doctorId") long doctorId, @Param("workDate") LocalDate workDate,
            @Param("status") EncounterStatus status, @Param("offset") long offset, @Param("size") int size);
    long countDoctorEncounters(@Param("doctorId") long doctorId, @Param("workDate") LocalDate workDate,
            @Param("status") EncounterStatus status);
    String lockPrescriptionStatus(@Param("encounterId") long encounterId);
}
