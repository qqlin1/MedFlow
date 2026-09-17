package com.qqlin.medflow.scheduling.repository;

import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.domain.SlotBookingSnapshot;
import com.qqlin.medflow.scheduling.domain.SlotDraft;
import com.qqlin.medflow.scheduling.domain.SlotStatus;
import com.qqlin.medflow.scheduling.mapper.SlotInsertParam;
import com.qqlin.medflow.scheduling.mapper.SlotMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SlotRepository {

    private final SlotMapper slotMapper;

    public SlotRepository(SlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    public void insertSlots(List<SlotDraft> slots) {
        List<SlotInsertParam> parameters = slots.stream()
                .map(slot -> new SlotInsertParam(
                        slot,
                        SlotStatus.OPEN
                ))
                .toList();

        slotMapper.insertSlots(parameters);
    }

    public Optional<SlotBookingSnapshot> findBookingSnapshot(
            long slotId
    ) {
        return Optional.ofNullable(
                slotMapper.findBookingSnapshot(slotId)
        );
    }

    /**
     * The condition remains in SQL so competing database clients re-check
     * capacity while taking the row lock for this UPDATE.
     */
    public boolean decreaseRemainingCapacityIfBookable(
            long slotId,
            LocalDateTime now
    ) {
        return slotMapper.decreaseRemainingCapacityIfBookable(
                slotId,
                now,
                SlotStatus.OPEN.name(),
                ScheduleStatus.PUBLISHED.name()
        ) == 1;
    }
}
