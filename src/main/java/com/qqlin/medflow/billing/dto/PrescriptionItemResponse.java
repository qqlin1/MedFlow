package com.qqlin.medflow.billing.dto;

import java.math.BigDecimal;

public record PrescriptionItemResponse(long id, long drugId, String drugCode, String drugName, String specification,
        String unit, int quantity, String instructions, BigDecimal unitPrice, BigDecimal lineAmount) {}
