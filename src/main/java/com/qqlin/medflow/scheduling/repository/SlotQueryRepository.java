package com.qqlin.medflow.scheduling.repository;

import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import com.qqlin.medflow.scheduling.mapper.SlotQueryMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SlotQueryRepository {

    private final SlotQueryMapper slotQueryMapper;

    public SlotQueryRepository(SlotQueryMapper slotQueryMapper) {
        this.slotQueryMapper = slotQueryMapper;
    }

    public LocalDateTime currentDatabaseTime() {
        return slotQueryMapper.currentDatabaseTime();
    }

    public long countAvailable(LocalDate workDate, Long departmentId,
                               Long doctorId, LocalDateTime now) {
        return slotQueryMapper.countAvailable(workDate, departmentId, doctorId, now);
    }

    public List<AvailableSlot> findAvailablePage(
            LocalDate workDate, Long departmentId, Long doctorId,
            LocalDateTime now, long offset, int limit
    ) {
        return slotQueryMapper.findAvailablePage(
                workDate, departmentId, doctorId, now, offset, limit);
    }
}
