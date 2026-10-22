package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

/** Read projection containing the exact database values used for pagination. */
public record AppointmentCursorItem(
        long id,
        long patientId,
        long slotId,
        AppointmentStatus status,
        LocalDateTime confirmDeadline,
        LocalDateTime cancelDeadline,
        LocalDateTime createdAt
) {
    public Appointment appointment() {
        return new Appointment(id, patientId, slotId, status,
                confirmDeadline, cancelDeadline);
    }

    public AppointmentCursor cursor() {
        return new AppointmentCursor(createdAt, id);
    }
}
