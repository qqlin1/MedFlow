package com.qqlin.medflow.appointment.domain;

public record CreateAppointmentCommand(
        long patientId,
        long slotId,
        String idempotencyKey
) {
}
