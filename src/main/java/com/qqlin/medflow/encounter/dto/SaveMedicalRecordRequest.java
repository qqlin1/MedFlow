package com.qqlin.medflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SaveMedicalRecordRequest(
        @Size(max = 2000) String chiefComplaint,
        @Size(max = 8000) String presentIllness,
        @Size(max = 4000) String diagnosis,
        @Size(max = 8000) String advice,
        @NotNull @PositiveOrZero Long version
) {}
