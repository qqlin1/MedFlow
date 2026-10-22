package com.qqlin.medflow.appointment.domain;

import java.util.List;

public record AppointmentDetail(
        Appointment appointment,
        List<AppointmentStatusChange> history
) {
}
