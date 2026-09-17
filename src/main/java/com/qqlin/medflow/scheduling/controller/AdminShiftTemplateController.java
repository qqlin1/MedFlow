package com.qqlin.medflow.scheduling.controller;

import com.qqlin.medflow.scheduling.dto.ShiftTemplateResponse;
import com.qqlin.medflow.scheduling.service.ShiftTemplateService;
import com.qqlin.medflow.shared.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/shift-templates")
public class AdminShiftTemplateController {

    private final ShiftTemplateService shiftTemplateService;

    public AdminShiftTemplateController(
            ShiftTemplateService shiftTemplateService
    ) {
        this.shiftTemplateService = shiftTemplateService;
    }

    @GetMapping
    public Result<List<ShiftTemplateResponse>> findEnabled() {
        List<ShiftTemplateResponse> responses = shiftTemplateService
                .findEnabled()
                .stream()
                .map(ShiftTemplateResponse::from)
                .toList();

        return Result.success(responses);
    }
}
