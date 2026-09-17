package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;

public record ConfirmAppointmentResponse(
        long appointmentId,
        AppointmentStatus status
) {

    public static ConfirmAppointmentResponse from(Appointment appointment) {
        return new ConfirmAppointmentResponse(
                appointment.id(),
                appointment.status()
        );
    }
}
