package com.qqlin.medflow.medication.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateDrugRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String specification,
        @NotBlank @Size(max = 20) String unit,
        @NotNull @DecimalMin("0.01") @DecimalMax("999999.99")
        @Digits(integer = 6, fraction = 2) BigDecimal unitPrice) {}
