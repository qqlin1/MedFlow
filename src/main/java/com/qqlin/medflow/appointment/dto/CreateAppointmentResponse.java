package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;

import java.time.LocalDateTime;

public record CreateAppointmentResponse(
        long appointmentId,
        AppointmentStatus status,
        LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline
) {

    public static CreateAppointmentResponse from(
            AppointmentCreationResult result
    ) {
        return new CreateAppointmentResponse(
                result.appointmentId(),
                result.status(),
                result.confirmDeadline(),
                result.cancelDeadline()
        );
    }
}
