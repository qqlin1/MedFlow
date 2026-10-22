package com.qqlin.medflow.encounter.dto;

import com.qqlin.medflow.encounter.domain.EncounterStatus;
import org.apache.ibatis.annotations.AutomapConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record EncounterSummary(
        long id, long appointmentId, long patientId, String patientName,
        long doctorId, String doctorName, String departmentName, String clinicRoomName,
        LocalDate workDate, LocalTime startTime, LocalTime endTime, EncounterStatus status,
        LocalDateTime checkedInAt, LocalDateTime startedAt, LocalDateTime completedAt, long version,
        Long sourceEncounterId, Long sourceAppointmentId, LocalDate sourceWorkDate, boolean hasBookingSnapshot
) {
    @AutomapConstructor
    public EncounterSummary {}

    public EncounterSummary(long id, long appointmentId, long patientId, String patientName,
            long doctorId, String doctorName, String departmentName, String clinicRoomName,
            LocalDate workDate, LocalTime startTime, LocalTime endTime, EncounterStatus status,
            LocalDateTime checkedInAt, LocalDateTime startedAt, LocalDateTime completedAt, long version) {
        this(id,appointmentId,patientId,patientName,doctorId,doctorName,departmentName,clinicRoomName,
                workDate,startTime,endTime,status,checkedInAt,startedAt,completedAt,version,
                null,null,null,false);
    }
}
