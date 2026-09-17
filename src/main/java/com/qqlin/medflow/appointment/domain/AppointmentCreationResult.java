package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

/**
 * Frozen success result persisted by the idempotency record and replayed to
 * retries with the same key.
 */
public record AppointmentCreationResult(
        long appointmentId,
        AppointmentStatus status,
        LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline
) {
}
