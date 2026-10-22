package com.qqlin.medflow.appointment.query;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

public record AppointmentVisitInfo(long appointmentId, long patientId, String patientName,
        String doctorName, String departmentName, String clinicRoomName, LocalDate workDate,
        LocalTime startTime, LocalTime endTime, String status, LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline, Long encounterId, String encounterStatus, boolean canCheckIn,
        String doctorTitle, Long sourceEncounterId, Long sourceAppointmentId, LocalDate sourceWorkDate,
        boolean hasBookingSnapshot) {
    @AutomapConstructor
    public AppointmentVisitInfo {}

    public AppointmentVisitInfo(long appointmentId, long patientId, String patientName,
            String doctorName, String departmentName, String clinicRoomName, LocalDate workDate,
            LocalTime startTime, LocalTime endTime, String status, LocalDateTime confirmDeadline,
            LocalDateTime cancelDeadline, Long encounterId, String encounterStatus, boolean canCheckIn) {
        this(appointmentId,patientId,patientName,doctorName,departmentName,clinicRoomName,workDate,
                startTime,endTime,status,confirmDeadline,cancelDeadline,encounterId,encounterStatus,canCheckIn,
                null,null,null,null,false);
    }

    /** Keep a composed voucher's action flags consistent with its one database clock. */
    public AppointmentVisitInfo withCheckInAvailability(boolean available) {
        return new AppointmentVisitInfo(appointmentId,patientId,patientName,doctorName,departmentName,
                clinicRoomName,workDate,startTime,endTime,status,confirmDeadline,cancelDeadline,
                encounterId,encounterStatus,available,doctorTitle,sourceEncounterId,sourceAppointmentId,
                sourceWorkDate,hasBookingSnapshot);
    }
}
