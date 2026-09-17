package com.qqlin.medflow.scheduling.controller;

import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.dto.CreateScheduleRequest;
import com.qqlin.medflow.scheduling.dto.CreateScheduleResponse;
import com.qqlin.medflow.scheduling.dto.PublishScheduleResponse;
import com.qqlin.medflow.scheduling.service.ScheduleService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/schedules")
public class AdminScheduleController {

    private final ScheduleService scheduleService;

    public AdminScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping
    public Result<CreateScheduleResponse> createDraft(
            @Valid @RequestBody CreateScheduleRequest request
    ) {
        long scheduleId = scheduleService.createDraft(
                new CreateScheduleCommand(
                        request.doctorId(),
                        request.clinicRoomId(),
                        request.shiftTemplateId(),
                        request.workDate()
                )
        );

        return Result.success(
                new CreateScheduleResponse(
                        scheduleId,
                        ScheduleStatus.DRAFT
                )
        );
    }

    @PostMapping("/{scheduleId}/publish")
    public Result<PublishScheduleResponse> publish(
            @PathVariable @Positive(message = "排班 ID 必须大于 0")
            long scheduleId
    ) {
        return Result.success(
                PublishScheduleResponse.from(
                        scheduleService.publish(scheduleId)
                )
        );
    }
}
