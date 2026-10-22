package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.SlotBookingSnapshot;
import com.qqlin.medflow.scheduling.repository.SlotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Narrow scheduling-module API used by the appointment module.
 * It joins the caller's surrounding transaction rather than creating a second
 * transaction boundary.
 */
@Service
public class SlotBookingService {

    private final SlotRepository slotRepository;

    public SlotBookingService(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    public Optional<SlotBookingSnapshot> lockBookingSnapshot(
            long slotId
    ) {
        return slotRepository.lockBookingSnapshot(slotId);
    }

    public LocalDateTime currentDatabaseTime() {
        return slotRepository.currentDatabaseTime();
    }

    public boolean decreaseRemainingCapacityIfBookable(
            long slotId,
            LocalDateTime now
    ) {
        return slotRepository.decreaseRemainingCapacityIfBookable(
                slotId,
                now
        );
    }

    public void lockForCapacityReturn(long slotId) {
        slotRepository.lockByIdForUpdate(slotId);
    }

    public void returnOneCapacity(long slotId) {
        slotRepository.increaseRemainingCapacityWithinLimit(slotId);
    }
}
