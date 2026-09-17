package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A persisted schedule shape required when publishing a draft.
 *
 * <p>The slot configuration belongs to the schedule snapshot, rather than to
 * the current shift template. This makes a later template change unable to
 * alter slots that have already been planned.</p>
 */
public record ScheduleForPublish(
        long id,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        int capacityPerSlot,
        ScheduleStatus status
) {
}
