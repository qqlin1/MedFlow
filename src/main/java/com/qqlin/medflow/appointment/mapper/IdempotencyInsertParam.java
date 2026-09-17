package com.qqlin.medflow.appointment.mapper;

import com.qqlin.medflow.appointment.domain.IdempotencyStatus;

public class IdempotencyInsertParam {

    private final long userId;
    private final String operationType;
    private final String idempotencyKey;
    private final String requestHash;
    private final IdempotencyStatus status;
    private Long id;

    public IdempotencyInsertParam(
            long userId,
            String operationType,
            String idempotencyKey,
            String requestHash,
            IdempotencyStatus status
    ) {
        this.userId = userId;
        this.operationType = operationType;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = status;
    }

    public long getUserId() {
        return userId;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
