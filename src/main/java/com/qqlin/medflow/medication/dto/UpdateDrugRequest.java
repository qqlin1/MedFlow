package com.qqlin.medflow.medication.dto;

import com.qqlin.medflow.medication.domain.DrugStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateDrugRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String specification,
        @NotBlank @Size(max = 20) String unit,
        @NotNull @DecimalMin("0.01") @DecimalMax("999999.99")
        @Digits(integer = 6, fraction = 2) BigDecimal unitPrice,
        @NotNull DrugStatus status,
        @NotNull @PositiveOrZero Long version) {}
