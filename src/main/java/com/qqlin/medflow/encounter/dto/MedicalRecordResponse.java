package com.qqlin.medflow.encounter.dto;

import java.time.LocalDateTime;

public record MedicalRecordResponse(long id, long encounterId, String chiefComplaint,
        String presentIllness, String diagnosis, String advice, long version, LocalDateTime updatedAt) {}
