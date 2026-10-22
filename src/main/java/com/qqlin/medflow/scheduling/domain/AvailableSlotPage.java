package com.qqlin.medflow.scheduling.domain;

import java.util.List;

public record AvailableSlotPage(
        List<AvailableSlot> items,
        long total,
        int page,
        int size
) {
}
