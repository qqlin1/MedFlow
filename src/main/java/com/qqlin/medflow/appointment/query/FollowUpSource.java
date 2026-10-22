package com.qqlin.medflow.appointment.query;

import java.time.LocalDate;

/** Patient-owned completed visit used only as context; it does not reopen or copy the old medical record. */
public record FollowUpSource(long encounterId, long appointmentId, long patientId, String patientName,
        long doctorId, String doctorName, long departmentId, String departmentName,
        LocalDate workDate, String diagnosis) {}
