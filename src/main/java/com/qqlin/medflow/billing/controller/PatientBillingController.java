package com.qqlin.medflow.billing.controller;

import com.qqlin.medflow.billing.dto.*;
import com.qqlin.medflow.billing.service.PrescriptionService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patient")
public class PatientBillingController {
    private final PrescriptionService service;
    private final JwtCurrentUserResolver users;

    public PatientBillingController(PrescriptionService service, JwtCurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/prescriptions")
    public Result<PatientPrescriptionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.patientPage(users.requireUserId(jwt), page, size));
    }

    @GetMapping("/prescriptions/{prescriptionId}")
    public Result<PrescriptionDetailResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long prescriptionId) {
        return Result.success(service.patientDetail(users.requireUserId(jwt), prescriptionId));
    }

    @PostMapping("/bills/{billId}/pay")
    public Result<PrescriptionDetailResponse> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long billId) {
        return Result.success(service.pay(users.requireUserId(jwt), billId));
    }
}
