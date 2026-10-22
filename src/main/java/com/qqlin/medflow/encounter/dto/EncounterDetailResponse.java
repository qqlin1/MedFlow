package com.qqlin.medflow.encounter.dto;

import java.util.List;

public record EncounterDetailResponse(EncounterSummary encounter, MedicalRecordResponse medicalRecord,
        List<EncounterHistoryResponse> history) {}
