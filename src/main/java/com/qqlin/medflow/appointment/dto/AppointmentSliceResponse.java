package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;

import java.util.List;

public record AppointmentSliceResponse(
        List<AppointmentResponse> items,
        boolean hasNext,
        AppointmentCursor nextCursor,
        int size
) {
    public static AppointmentSliceResponse from(AppointmentSlice slice) {
        return new AppointmentSliceResponse(
                slice.items().stream()
                        .map(item -> AppointmentResponse.from(item.appointment()))
                        .toList(),
                slice.hasNext(), slice.nextCursor(), slice.size());
    }
}
