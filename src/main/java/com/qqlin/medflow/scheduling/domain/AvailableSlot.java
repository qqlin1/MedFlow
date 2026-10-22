package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** A query snapshot; booking must recheck state, deadline and remaining capacity. */
public record AvailableSlot(
        long slotId,
        long scheduleId,
        long departmentId,
        String departmentName,
        long doctorId,
        String doctorName,
        long clinicRoomId,
        String clinicRoomName,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime bookingDeadline,
        int remainingCapacity
) {
}
