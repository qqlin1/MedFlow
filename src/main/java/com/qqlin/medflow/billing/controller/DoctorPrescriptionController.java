package com.qqlin.medflow.billing.controller;

import com.qqlin.medflow.billing.dto.*;
import com.qqlin.medflow.billing.service.PrescriptionService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/doctor/encounters/{encounterId}/prescription")
public class DoctorPrescriptionController {
    private final PrescriptionService service;
    private final JwtCurrentUserResolver users;

    public DoctorPrescriptionController(PrescriptionService service, JwtCurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public Result<PrescriptionDetailResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId) {
        return Result.success(service.doctorDetail(users.requireUserId(jwt), encounterId));
    }

    @PutMapping
    public Result<PrescriptionDetailResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId,
            @Valid @RequestBody SavePrescriptionRequest request) {
        return Result.success(service.save(users.requireUserId(jwt), encounterId, request));
    }

    @PostMapping("/issue")
    public Result<PrescriptionDetailResponse> issue(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId,
            @Valid @RequestBody IssuePrescriptionRequest request) {
        return Result.success(service.issue(users.requireUserId(jwt), encounterId, request.version()));
    }

    @PostMapping("/discard")
    public Result<PrescriptionDetailResponse> discard(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId,
            @Valid @RequestBody IssuePrescriptionRequest request) {
        return Result.success(service.discard(users.requireUserId(jwt), encounterId, request.version()));
    }
}
