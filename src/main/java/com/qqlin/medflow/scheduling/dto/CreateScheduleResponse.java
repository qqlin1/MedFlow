package com.qqlin.medflow.scheduling.dto;

import com.qqlin.medflow.scheduling.domain.ScheduleStatus;

public record CreateScheduleResponse(
        long scheduleId,
        ScheduleStatus status
) {
}
