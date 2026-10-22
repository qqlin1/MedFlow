package com.qqlin.medflow.billing.domain;

import java.math.BigDecimal;

public record PrescriptionRow(long id, long encounterId, PrescriptionStatus status, BigDecimal totalAmount, long version) {}
