package com.qqlin.medflow.devtools.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class WebCheckRequest {
    @NotNull(message = "就诊人ID不能为空")
    @Positive(message = "就诊ID必须大于0")
    private Long patientId;
    @NotNull(message = "号源ID不能为空")
    @Positive(message = "号源ID必须大于0")
    private Long slotId;
    public WebCheckRequest(){}

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Long getSlotId() {
        return slotId;
    }

    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }
}
