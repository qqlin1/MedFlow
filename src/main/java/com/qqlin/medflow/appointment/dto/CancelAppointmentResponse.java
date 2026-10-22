package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;

public record CancelAppointmentResponse(
        long appointmentId,
        AppointmentStatus status
) {

    public static CancelAppointmentResponse from(Appointment appointment) {
        return new CancelAppointmentResponse(
                appointment.id(),
                appointment.status()
        );
    }
}
