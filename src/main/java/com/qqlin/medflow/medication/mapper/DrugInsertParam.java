package com.qqlin.medflow.medication.mapper;

import java.math.BigDecimal;

/** Mutable generated key holder, restricted to the persistence boundary. */
public class DrugInsertParam {
    private Long id;
    private final String code;
    private final String name;
    private final String specification;
    private final String unit;
    private final BigDecimal unitPrice;

    public DrugInsertParam(String code, String name, String specification,
            String unit, BigDecimal unitPrice) {
        this.code = code;
        this.name = name;
        this.specification = specification;
        this.unit = unit;
        this.unitPrice = unitPrice;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSpecification() { return specification; }
    public String getUnit() { return unit; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
