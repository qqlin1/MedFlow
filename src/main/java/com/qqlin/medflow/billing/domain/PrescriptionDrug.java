package com.qqlin.medflow.billing.domain;

import java.math.BigDecimal;

/** Live catalog data read under a row lock before saving/issuing. */
public record PrescriptionDrug(long id, String code, String name, String specification, String unit,
        BigDecimal unitPrice, String status) {}
