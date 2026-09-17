package com.qqlin.medflow.appointment.mapper;

import com.qqlin.medflow.appointment.domain.IdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdempotencyMapper {

    int insertProcessing(IdempotencyInsertParam parameter);

    IdempotencyRecord lockByUniqueKey(
            @Param("userId") long userId,
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey
    );

    int markSuccess(
            @Param("idempotencyRecordId") long idempotencyRecordId,
            @Param("appointmentId") long appointmentId,
            @Param("resultJson") String resultJson,
            @Param("successStatus") String successStatus,
            @Param("processingStatus") String processingStatus
    );
}
