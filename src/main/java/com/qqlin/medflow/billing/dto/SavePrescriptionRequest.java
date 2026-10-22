package com.qqlin.medflow.billing.dto;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.util.List;

public record SavePrescriptionRequest(
        @NotNull @PositiveOrZero @JsonDeserialize(using = StrictLongDeserializer.class) Long version,
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long drugId,
            @NotNull @Min(1) @Max(1000) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity,
            @Size(max = 1000) String instructions) {}
}
