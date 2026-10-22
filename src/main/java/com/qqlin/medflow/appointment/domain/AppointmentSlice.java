package com.qqlin.medflow.appointment.domain;

import java.util.List;

public record AppointmentSlice(
        List<AppointmentCursorItem> items,
        boolean hasNext,
        AppointmentCursor nextCursor,
        int size
) {
    public AppointmentSlice {
        items = List.copyOf(items);
    }
}
