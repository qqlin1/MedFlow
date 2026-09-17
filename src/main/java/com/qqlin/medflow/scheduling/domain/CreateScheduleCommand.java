package com.qqlin.medflow.scheduling.domain;

import java.time.LocalDate;

public record CreateScheduleCommand(
        long doctorId,
        long clinicRoomId,
        long shiftTemplateId,
        LocalDate workDate
) {
}
