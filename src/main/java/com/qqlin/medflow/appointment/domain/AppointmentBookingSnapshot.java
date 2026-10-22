package com.qqlin.medflow.appointment.domain;

import java.time.LocalDateTime;

/** Directory labels observed within the creation transaction, never backfilled for old appointments. */
public record AppointmentBookingSnapshot(String patientName, String doctorName, String doctorTitle,
        String departmentName, String clinicRoomName, LocalDateTime capturedAt) {}
