package com.qqlin.medflow.appointment.repository;

import com.qqlin.medflow.appointment.domain.IdempotencyRecord;
import com.qqlin.medflow.appointment.domain.IdempotencyStatus;
import com.qqlin.medflow.appointment.mapper.IdempotencyInsertParam;
import com.qqlin.medflow.appointment.mapper.IdempotencyMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final IdempotencyMapper idempotencyMapper;

    public IdempotencyRepository(IdempotencyMapper idempotencyMapper) {
        this.idempotencyMapper = idempotencyMapper;
    }

    public long insertProcessing(
            long userId,
            String operationType,
            String idempotencyKey,
            String requestHash
    ) {
        IdempotencyInsertParam parameter =
                new IdempotencyInsertParam(
                        userId,
                        operationType,
                        idempotencyKey,
                        requestHash,
                        IdempotencyStatus.PROCESSING
                );

        int affectedRows = idempotencyMapper.insertProcessing(parameter);

        if (affectedRows != 1 || parameter.getId() == null) {
            throw new IllegalStateException(
                    "创建幂等记录后没有获得主键"
            );
        }

        return parameter.getId();
    }

    public Optional<IdempotencyRecord> lockByUniqueKey(
            long userId,
            String operationType,
            String idempotencyKey
    ) {
        return Optional.ofNullable(
                idempotencyMapper.lockByUniqueKey(
                        userId,
                        operationType,
                        idempotencyKey
                )
        );
    }

    public boolean markSuccess(
            long idempotencyRecordId,
            long appointmentId,
            String resultJson
    ) {
        return idempotencyMapper.markSuccess(
                idempotencyRecordId,
                appointmentId,
                resultJson,
                IdempotencyStatus.SUCCESS.name(),
                IdempotencyStatus.PROCESSING.name()
        ) == 1;
    }
}
