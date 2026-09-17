package com.qqlin.medflow.appointment.domain;

public record IdempotencyRecord(
        long id,
        long userId,
        String operationType,
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        Long appointmentId,
        String resultJson
) {
}
