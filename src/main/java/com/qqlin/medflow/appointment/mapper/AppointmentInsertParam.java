package com.qqlin.medflow.appointment.mapper;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;

import java.time.LocalDateTime;

public class AppointmentInsertParam {

    private final long patientId;
    private final long slotId;
    private final AppointmentStatus status;
    private final LocalDateTime confirmDeadline;
    private final LocalDateTime cancelDeadline;
    private Long id;

    public AppointmentInsertParam(Appointment appointment) {
        this.patientId = appointment.patientId();
        this.slotId = appointment.slotId();
        this.status = appointment.status();
        this.confirmDeadline = appointment.confirmDeadline();
        this.cancelDeadline = appointment.cancelDeadline();
    }

    public long getPatientId() {
        return patientId;
    }

    public long getSlotId() {
        return slotId;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public LocalDateTime getConfirmDeadline() {
        return confirmDeadline;
    }

    public LocalDateTime getCancelDeadline() {
        return cancelDeadline;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
