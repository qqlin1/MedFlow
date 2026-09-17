package com.qqlin.medflow.appointment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAppointmentRequest(

        @NotNull(message = "就诊人 ID 不能为空")
        @Positive(message = "就诊人 ID 必须大于 0")
        Long patientId,

        @NotNull(message = "号源 ID 不能为空")
        @Positive(message = "号源 ID 必须大于 0")
        Long slotId
) {
}
