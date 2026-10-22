package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/schedules")
public class AdminScheduleManagementController {
    private final ScheduleManagementService service;
    private final JwtCurrentUserResolver users;
    public AdminScheduleManagementController(ScheduleManagementService service, JwtCurrentUserResolver users) {
        this.service = service; this.users = users;
    }
    @GetMapping
    public Result<Page<ScheduleItem>> findPage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) ScheduleStatus status,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.findPage(from, to, doctorId, departmentId, status, page, size));
    }
    @GetMapping("/{id}")
    public Result<ScheduleDetail> detail(@PathVariable @Positive long id) {
        return Result.success(service.detail(id));
    }
    @PostMapping("/batch")
    public Result<BatchResult> createBatch(@Valid @RequestBody BatchRequest request) {
        return Result.success(service.createBatch(request));
    }
    @PostMapping("/{id}/cancel")
    public Result<ScheduleItem> cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long id, @Valid @RequestBody CancelRequest request) {
        return Result.success(service.cancel(id, users.requireUserId(jwt), request.reason()));
    }
}
