package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDateTime;

/**
 * The small immutable view required by the appointment write transaction.
 */
public record SlotBookingSnapshot(
        long slotId,
        LocalDateTime bookingDeadline,
        int remainingCapacity,
        SlotStatus slotStatus,
        ScheduleStatus scheduleStatus
) {
}
