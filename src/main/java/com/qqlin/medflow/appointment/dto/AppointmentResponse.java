package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;

import java.time.LocalDateTime;

public record AppointmentResponse(
        long appointmentId,
        long patientId,
        long slotId,
        AppointmentStatus status,
        LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline
) {
    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.id(),
                appointment.patientId(),
                appointment.slotId(),
                appointment.status(),
                appointment.confirmDeadline(),
                appointment.cancelDeadline()
        );
    }
}
