package com.qqlin.medflow.encounter.dto;

import com.qqlin.medflow.encounter.domain.EncounterStatus;
import java.time.LocalDateTime;

public record EncounterHistoryResponse(long id, EncounterStatus fromStatus, EncounterStatus toStatus,
        String eventType, long actorUserId, LocalDateTime createdAt) {}
