package com.qqlin.medflow.scheduling.dto;

import com.qqlin.medflow.scheduling.domain.AvailableSlotPage;

import java.util.List;

public record AvailableSlotPageResponse(
        List<AvailableSlotResponse> items,
        long total,
        int page,
        int size
) {
    public static AvailableSlotPageResponse from(AvailableSlotPage result) {
        return new AvailableSlotPageResponse(
                result.items().stream().map(AvailableSlotResponse::from).toList(),
                result.total(), result.page(), result.size()
        );
    }
}
