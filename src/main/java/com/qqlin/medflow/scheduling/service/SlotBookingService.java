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

    public Optional<SlotBookingSnapshot> findBookingSnapshot(
            long slotId
    ) {
        return slotRepository.findBookingSnapshot(slotId);
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
}
