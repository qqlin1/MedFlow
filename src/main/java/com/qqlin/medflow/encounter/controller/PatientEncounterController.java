package com.qqlin.medflow.encounter.controller;

import com.qqlin.medflow.encounter.dto.EncounterDetailResponse;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/appointments")
public class PatientEncounterController {
    private final EncounterService service;
    private final JwtCurrentUserResolver users;

    public PatientEncounterController(EncounterService service, JwtCurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/{appointmentId}/check-in")
    public Result<EncounterDetailResponse> checkIn(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long appointmentId) {
        return Result.success(service.checkIn(users.requireUserId(jwt), appointmentId));
    }

    @GetMapping("/{appointmentId}/encounter")
    public Result<EncounterDetailResponse> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long appointmentId) {
        return Result.success(service.patientDetail(users.requireUserId(jwt), appointmentId));
    }
}
