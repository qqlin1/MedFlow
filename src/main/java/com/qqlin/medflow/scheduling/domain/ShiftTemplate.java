package com.qqlin.medflow.scheduling.domain;

import java.time.LocalTime;

public record ShiftTemplate(
        long id,
        String code,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        int capacityPerSlot,
        ShiftTemplateStatus status
) {
}
