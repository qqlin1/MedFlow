package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/doctor")
public class DoctorScheduleController {
    private final ScheduleManagementService service;
    private final JwtCurrentUserResolver users;
    public DoctorScheduleController(ScheduleManagementService service, JwtCurrentUserResolver users) {
        this.service = service; this.users = users;
    }
    @GetMapping("/schedules")
    public Result<Page<ScheduleItem>> schedules(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ScheduleStatus status,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.findDoctorPage(users.requireUserId(jwt), from, to, status, page, size));
    }
    @GetMapping("/appointments")
    public Result<Page<DoctorAppointment>> appointments(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.doctorAppointments(users.requireUserId(jwt), workDate, page, size));
    }
}
