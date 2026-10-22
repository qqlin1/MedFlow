package com.qqlin.medflow.billing.dto;

import com.qqlin.medflow.billing.domain.PrescriptionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PatientPrescriptionItem(long id, long encounterId, long appointmentId, String patientName,
        String doctorName, String departmentName, PrescriptionStatus status, BigDecimal totalAmount,
        long version, LocalDateTime createdAt, LocalDateTime issuedAt, BillResponse bill) {
    public static PatientPrescriptionItem from(PrescriptionSummary p, BillResponse bill) {
        return new PatientPrescriptionItem(p.id(), p.encounterId(), p.appointmentId(), p.patientName(), p.doctorName(),
                p.departmentName(), p.status(), p.totalAmount(), p.version(), p.createdAt(), p.issuedAt(), bill);
    }
}
