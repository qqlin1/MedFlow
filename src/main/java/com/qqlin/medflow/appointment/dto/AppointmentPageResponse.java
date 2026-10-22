package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.AppointmentPage;

import java.util.List;

public record AppointmentPageResponse(
        List<AppointmentResponse> items,
        long total,
        int page,
        int size
) {
    public static AppointmentPageResponse from(AppointmentPage result) {
        return new AppointmentPageResponse(
                result.items().stream().map(AppointmentResponse::from).toList(),
                result.total(),
                result.page(),
                result.size()
        );
    }
}
