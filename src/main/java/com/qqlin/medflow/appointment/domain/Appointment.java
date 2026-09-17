package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

public record Appointment(
        long id,
        long patientId,
        long slotId,
        AppointmentStatus status,
        LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline
) {
}
