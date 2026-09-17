package com.qqlin.medflow.scheduling.repository;

import com.qqlin.medflow.scheduling.domain.ScheduleDraft;
import com.qqlin.medflow.scheduling.domain.ScheduleForPublish;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.mapper.ScheduleDraftInsertParam;
import com.qqlin.medflow.scheduling.mapper.ScheduleMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Exposes scheduling persistence operations while Mapper XML owns the SQL.
 */
@Repository
public class ScheduleRepository {

    private final ScheduleMapper scheduleMapper;

    public ScheduleRepository(ScheduleMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    public boolean existsDoctorOverlap(ScheduleDraft draft) {
        return scheduleMapper.findDoctorOverlap(
                draft.doctorId(),
                draft.workDate(),
                draft.endTime(),
                draft.startTime()
        ) != null;
    }

    public boolean existsClinicRoomOverlap(ScheduleDraft draft) {
        return scheduleMapper.findClinicRoomOverlap(
                draft.clinicRoomId(),
                draft.workDate(),
                draft.endTime(),
                draft.startTime()
        ) != null;
    }

    public long insertDraft(ScheduleDraft draft) {
        ScheduleDraftInsertParam parameter =
                new ScheduleDraftInsertParam(
                        draft,
                        ScheduleStatus.DRAFT
                );

        int affectedRows = scheduleMapper.insertDraft(parameter);

        if (affectedRows != 1 || parameter.getId() == null) {
            throw new IllegalStateException(
                    "创建排班后没有获得主键"
            );
        }

        return parameter.getId();
    }

    public Optional<ScheduleForPublish> lockScheduleForPublish(
            long scheduleId
    ) {
        return Optional.ofNullable(
                scheduleMapper.lockScheduleForPublish(scheduleId)
        );
    }

    public boolean markPublishedIfDraft(long scheduleId) {
        return scheduleMapper.markPublishedIfDraft(
                scheduleId,
                ScheduleStatus.PUBLISHED.name(),
                ScheduleStatus.DRAFT.name()
        ) == 1;
    }
}
