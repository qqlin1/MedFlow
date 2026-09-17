package com.qqlin.medflow.scheduling.dto;

import com.qqlin.medflow.scheduling.domain.ShiftTemplate;

import java.time.LocalTime;

public record ShiftTemplateResponse(
        long id,
        String code,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        int capacityPerSlot
) {

    public static ShiftTemplateResponse from(
            ShiftTemplate shiftTemplate
    ) {
        return new ShiftTemplateResponse(
                shiftTemplate.id(),
                shiftTemplate.code(),
                shiftTemplate.name(),
                shiftTemplate.startTime(),
                shiftTemplate.endTime(),
                shiftTemplate.slotDurationMinutes(),
                shiftTemplate.capacityPerSlot()
        );
    }
}
