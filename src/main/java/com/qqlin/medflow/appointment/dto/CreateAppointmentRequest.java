package com.qqlin.medflow.appointment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAppointmentRequest(

        @NotNull(message = "就诊人 ID 不能为空")
        @Positive(message = "就诊人 ID 必须大于 0")
        Long patientId,

        @NotNull(message = "号源 ID 不能为空")
        @Positive(message = "号源 ID 必须大于 0")
        Long slotId,

        @Positive(message = "来源接诊 ID 必须大于 0")
        @JsonDeserialize(using = SourceEncounterIdDeserializer.class)
        Long sourceEncounterId
) {
    public CreateAppointmentRequest(Long patientId, Long slotId) {
        this(patientId, slotId, null);
    }
}
