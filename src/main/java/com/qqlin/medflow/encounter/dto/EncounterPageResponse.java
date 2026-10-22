package com.qqlin.medflow.encounter.dto;

import java.util.List;

public record EncounterPageResponse(List<EncounterSummary> items, long total, int page, int size) {}
