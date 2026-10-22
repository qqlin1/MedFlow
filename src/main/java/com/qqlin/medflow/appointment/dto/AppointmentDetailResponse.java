package com.qqlin.medflow.appointment.dto;

import com.qqlin.medflow.appointment.domain.AppointmentDetail;
import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;

import java.util.List;

public record AppointmentDetailResponse(
        AppointmentResponse appointment,
        List<AppointmentStatusChange> history
) {
    public static AppointmentDetailResponse from(AppointmentDetail detail) {
        return new AppointmentDetailResponse(
                AppointmentResponse.from(detail.appointment()),
                detail.history()
        );
    }
}
