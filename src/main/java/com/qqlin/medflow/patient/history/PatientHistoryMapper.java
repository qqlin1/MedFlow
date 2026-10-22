package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.encounter.dto.EncounterHistoryResponse;
import com.qqlin.medflow.encounter.dto.MedicalRecordResponse;
import com.qqlin.medflow.patient.domain.Patient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface PatientHistoryMapper {
    Patient findOwnedPatient(@Param("userId") long userId,@Param("patientId") long patientId);
    Patient findDoctorAnchorPatient(@Param("userId") long userId,@Param("anchorAppointmentId") long anchorAppointmentId);
    List<HistoryVisit> findPage(@Param("patientId") long patientId,@Param("offset") long offset,@Param("size") int size);
    long count(@Param("patientId") long patientId);
    HistoryVisit findVisit(@Param("patientId") long patientId,@Param("appointmentId") long appointmentId);
    MedicalRecordResponse findCompletedMedicalRecord(@Param("encounterId") long encounterId);
    List<AppointmentStatusChange> findAppointmentHistory(@Param("appointmentId") long appointmentId);
    List<EncounterHistoryResponse> findEncounterHistory(@Param("encounterId") long encounterId);
}
