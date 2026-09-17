package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.ScheduleDraft;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;

import java.time.LocalDate;
import java.time.LocalTime;

public class ScheduleDraftInsertParam {

    private final long doctorId;
    private final long clinicRoomId;
    private final long shiftTemplateId;
    private final LocalDate workDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final int slotDurationMinutes;
    private final int capacityPerSlot;
    private final ScheduleStatus status;
    private Long id;

    public ScheduleDraftInsertParam(
            ScheduleDraft draft,
            ScheduleStatus status
    ) {
        this.doctorId = draft.doctorId();
        this.clinicRoomId = draft.clinicRoomId();
        this.shiftTemplateId = draft.shiftTemplateId();
        this.workDate = draft.workDate();
        this.startTime = draft.startTime();
        this.endTime = draft.endTime();
        this.slotDurationMinutes = draft.slotDurationMinutes();
        this.capacityPerSlot = draft.capacityPerSlot();
        this.status = status;
    }

    public long getDoctorId() {
        return doctorId;
    }

    public long getClinicRoomId() {
        return clinicRoomId;
    }

    public long getShiftTemplateId() {
        return shiftTemplateId;
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

    public int getSlotDurationMinutes() {
        return slotDurationMinutes;
    }

    public int getCapacityPerSlot() {
        return capacityPerSlot;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
