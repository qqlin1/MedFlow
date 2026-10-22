package com.qqlin.medflow.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record IssuePrescriptionRequest(@NotNull @PositiveOrZero @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {}
