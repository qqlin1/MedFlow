package com.qqlin.medflow.appointment.domain;

public record CreateAppointmentCommand(
        long patientId,
        long slotId,
        String idempotencyKey,
        Long sourceEncounterId
) {
    public CreateAppointmentCommand(long patientId, long slotId, String idempotencyKey) {
        this(patientId, slotId, idempotencyKey, null);
    }
}
