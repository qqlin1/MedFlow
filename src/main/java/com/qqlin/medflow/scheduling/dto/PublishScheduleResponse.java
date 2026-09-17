package com.qqlin.medflow.scheduling.dto;

import com.qqlin.medflow.scheduling.domain.PublishScheduleResult;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;

public record PublishScheduleResponse(
        long scheduleId,
        ScheduleStatus status,
        int generatedSlotCount
) {

    public static PublishScheduleResponse from(
            PublishScheduleResult result
    ) {
        return new PublishScheduleResponse(
                result.scheduleId(),
                result.status(),
                result.generatedSlotCount()
        );
    }
}
