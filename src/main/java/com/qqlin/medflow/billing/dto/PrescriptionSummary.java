package com.qqlin.medflow.billing.dto;

import com.qqlin.medflow.billing.domain.PrescriptionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PrescriptionSummary(long id, long encounterId, long appointmentId, String patientName,
        String doctorName, String departmentName, PrescriptionStatus status, BigDecimal totalAmount,
        long version, LocalDateTime createdAt, LocalDateTime issuedAt) {}
