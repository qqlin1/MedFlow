package com.qqlin.medflow.scheduling.domain;

public record PublishScheduleResult(
        long scheduleId,
        ScheduleStatus status,
        int generatedSlotCount
) {
}
