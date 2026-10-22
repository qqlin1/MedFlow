package com.qqlin.medflow.scheduling.controller;

import com.qqlin.medflow.scheduling.dto.AvailableSlotPageResponse;
import com.qqlin.medflow.scheduling.service.SlotQueryService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/patient/slots")
public class PatientSlotController {

    private final SlotQueryService slotQueryService;

    public PatientSlotController(SlotQueryService slotQueryService) {
        this.slotQueryService = slotQueryService;
    }

    @GetMapping
    public Result<AvailableSlotPageResponse> findAvailable(
            @RequestParam(required = false)
            @NotNull(message = "预约日期不能为空")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate workDate,
            @RequestParam(required = false)
            @Positive(message = "科室 ID 必须大于 0") Long departmentId,
            @RequestParam(required = false)
            @Positive(message = "医生 ID 必须大于 0") Long doctorId,
            @RequestParam(defaultValue = "1")
            @Positive(message = "页码必须大于 0") int page,
            @RequestParam(defaultValue = "20")
            @Positive(message = "每页数量必须大于 0")
            @Max(value = SlotQueryService.MAX_PAGE_SIZE, message = "每页最多 50 条")
            int size
    ) {
        return Result.success(AvailableSlotPageResponse.from(
                slotQueryService.findAvailable(workDate, departmentId, doctorId, page, size)
        ));
    }
}
