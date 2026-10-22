package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

public record AppointmentCursor(LocalDateTime createdAt, long id) {
}
