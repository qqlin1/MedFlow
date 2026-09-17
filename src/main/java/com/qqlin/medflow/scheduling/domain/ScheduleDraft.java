package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleDraft(
        long doctorId,
        long clinicRoomId,
        long shiftTemplateId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        int capacityPerSlot
) {
}
