package com.qqlin.medflow.appointment.query;

import java.time.LocalDateTime;

/** A patient-facing read view; appointment, encounter and payment stay independent. */
public record AppointmentVoucher(
        AppointmentVisitInfo visit,
        String relationship,
        String maskedPhone,
        LocalDateTime databaseNow,
        LocalDateTime checkInOpensAt,
        LocalDateTime checkInClosesAt,
        String progress,
        boolean canConfirm,
        boolean canCancel,
        boolean canCheckIn,
        AppointmentVoucherBill bill
) {}
