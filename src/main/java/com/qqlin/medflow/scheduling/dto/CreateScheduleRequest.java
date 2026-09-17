package com.qqlin.medflow.scheduling.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateScheduleRequest(

        @NotNull(message = "医生 ID 不能为空")
        Long doctorId,

        @NotNull(message = "诊室 ID 不能为空")
        Long clinicRoomId,

        @NotNull(message = "班次模板 ID 不能为空")
        Long shiftTemplateId,

        @NotNull(message = "工作日期不能为空")
        @Future(message = "工作日期必须晚于今天")
        LocalDate workDate
) {
}
