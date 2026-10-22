package com.qqlin.medflow.appointment.domain;

import java.util.List;

public record AppointmentPage(
        List<Appointment> items,
        long total,
        int page,
        int size
) {
}
