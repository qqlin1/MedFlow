package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

public record AppointmentStatusChange(
        long id,
        AppointmentStatus fromStatus,
        AppointmentStatus toStatus,
        AppointmentEventType eventType,
        Long actorUserId,
        LocalDateTime createdAt
) {
}
