package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.SlotDraft;
import com.qqlin.medflow.scheduling.domain.SlotStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class SlotInsertParam {

    private final long scheduleId;
    private final LocalDate workDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final LocalDateTime bookingDeadline;
    private final int totalCapacity;
    private final int remainingCapacity;
    private final SlotStatus status;

    public SlotInsertParam(SlotDraft draft, SlotStatus status) {
        this.scheduleId = draft.scheduleId();
        this.workDate = draft.workDate();
        this.startTime = draft.startTime();
        this.endTime = draft.endTime();
        this.bookingDeadline = draft.bookingDeadline();
        this.totalCapacity = draft.capacity();
        this.remainingCapacity = draft.capacity();
        this.status = status;
    }

    public long getScheduleId() {
        return scheduleId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public LocalDateTime getBookingDeadline() {
        return bookingDeadline;
    }

    public int getTotalCapacity() {
        return totalCapacity;
    }

    public int getRemainingCapacity() {
        return remainingCapacity;
    }

    public SlotStatus getStatus() {
        return status;
    }
}
