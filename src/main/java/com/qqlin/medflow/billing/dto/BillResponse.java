package com.qqlin.medflow.billing.dto;

import com.qqlin.medflow.billing.domain.BillStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BillResponse(long id, long prescriptionId, BillStatus status, BigDecimal totalAmount, LocalDateTime paidAt) {}
