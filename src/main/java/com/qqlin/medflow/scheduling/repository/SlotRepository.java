package com.qqlin.medflow.scheduling.repository;

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

    public Optional<SlotBookingSnapshot> lockBookingSnapshot(
            long slotId
    ) {
        return Optional.ofNullable(
                slotMapper.lockBookingSnapshot(slotId)
        );
    }

    public LocalDateTime currentDatabaseTime() {
        return slotMapper.currentDatabaseTime();
    }

    /**
     * Caller has locked the slot exclusively and its published schedule in
     * share mode. Keep capacity/deadline guards here without upgrading the
     * parent schedule's shared lock through a joined UPDATE.
     */
    public boolean decreaseRemainingCapacityIfBookable(
            long slotId,
            LocalDateTime now
    ) {
        return slotMapper.decreaseRemainingCapacityIfBookable(
                slotId,
                now,
                SlotStatus.OPEN.name()
        ) == 1;
    }

    public void lockByIdForUpdate(long slotId) {
        Long lockedSlotId = slotMapper.lockByIdForUpdate(slotId);

        if (lockedSlotId == null) {
            throw new IllegalStateException("预约关联的号源不存在");
        }
    }

    public void increaseRemainingCapacityWithinLimit(long slotId) {
        int affectedRows = slotMapper
                .increaseRemainingCapacityWithinLimit(slotId);

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "归还号源失败：号源不存在或剩余容量已达到上限"
            );
        }
    }
}
