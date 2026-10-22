package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

/** Database deadline and ID form a stable ascending scan cursor. */
public record AppointmentExpirationCandidate(long id, LocalDateTime confirmDeadline) {
}
