package com.qqlin.medflow.appointment.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Only an issued prescription's actual bill is visible, never a doctor's draft. */
public record AppointmentVoucherBill(
        long prescriptionId,
        long billId,
        String status,
        BigDecimal totalAmount,
        LocalDateTime paidAt
) {}
