package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A slot calculated from one immutable schedule snapshot, before persistence.
 */
public record SlotDraft(
        long scheduleId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime bookingDeadline,
        int capacity
) {
}
