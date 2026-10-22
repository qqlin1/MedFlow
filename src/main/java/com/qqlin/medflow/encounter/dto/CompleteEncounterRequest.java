package com.qqlin.medflow.encounter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CompleteEncounterRequest(@NotNull @PositiveOrZero Long version) {}
