package com.qqlin.medflow.medication.domain;

import java.math.BigDecimal;

/** Prices are catalog values; prescriptions retain their own immutable snapshot. */
public record Drug(long id, String code, String name, String specification,
        String unit, BigDecimal unitPrice, DrugStatus status, long version) {}
