package com.qqlin.medflow.scheduling.dto;

import com.qqlin.medflow.scheduling.domain.AvailableSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AvailableSlotResponse(
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
    public static AvailableSlotResponse from(AvailableSlot slot) {
        return new AvailableSlotResponse(
                slot.slotId(), slot.scheduleId(),
                slot.departmentId(), slot.departmentName(),
                slot.doctorId(), slot.doctorName(),
                slot.clinicRoomId(), slot.clinicRoomName(),
                slot.workDate(), slot.startTime(), slot.endTime(),
                slot.bookingDeadline(), slot.remainingCapacity()
        );
    }
}
