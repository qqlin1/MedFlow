package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.billing.domain.BillStatus;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import org.apache.ibatis.annotations.AutomapConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record HistoryVisit(long appointmentId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
        long doctorId, String doctorName, String departmentName, String clinicRoomName,
        AppointmentStatus appointmentStatus, Long encounterId, EncounterStatus encounterStatus,
        String chiefComplaint, String diagnosis, Long prescriptionId, BillStatus billStatus, BigDecimal totalAmount,
        String patientName, String doctorTitle, Long sourceEncounterId, Long sourceAppointmentId,
        LocalDate sourceWorkDate, boolean hasBookingSnapshot) {
    @AutomapConstructor
    public HistoryVisit {}

    public HistoryVisit(long appointmentId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
            long doctorId, String doctorName, String departmentName, String clinicRoomName,
            AppointmentStatus appointmentStatus, Long encounterId, EncounterStatus encounterStatus,
            String chiefComplaint, String diagnosis, Long prescriptionId, BillStatus billStatus, BigDecimal totalAmount) {
        this(appointmentId,workDate,startTime,endTime,doctorId,doctorName,departmentName,clinicRoomName,
                appointmentStatus,encounterId,encounterStatus,chiefComplaint,diagnosis,prescriptionId,billStatus,
                totalAmount,null,null,null,null,null,false);
    }
}
