package com.qqlin.medflow.encounter.controller;

import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.*;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/doctor/encounters")
public class DoctorEncounterController {
    private final EncounterService service;
    private final JwtCurrentUserResolver users;

    public DoctorEncounterController(EncounterService service, JwtCurrentUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public Result<EncounterPageResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @RequestParam(required = false) EncounterStatus status,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.doctorQueue(users.requireUserId(jwt), workDate, status, page, size));
    }

    @GetMapping("/{encounterId}")
    public Result<EncounterDetailResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId) {
        return Result.success(service.doctorDetail(users.requireUserId(jwt), encounterId));
    }

    @PostMapping("/{encounterId}/start")
    public Result<EncounterDetailResponse> start(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId) {
        return Result.success(service.start(users.requireUserId(jwt), encounterId));
    }

    @PostMapping("/{encounterId}/skip")
    public Result<EncounterDetailResponse> skip(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId) {
        return Result.success(service.skip(users.requireUserId(jwt), encounterId));
    }

    @PutMapping("/{encounterId}/medical-record")
    public Result<EncounterDetailResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId,
            @Valid @RequestBody SaveMedicalRecordRequest request) {
        return Result.success(service.saveMedicalRecord(users.requireUserId(jwt), encounterId, request));
    }

    @PostMapping("/{encounterId}/complete")
    public Result<EncounterDetailResponse> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long encounterId,
            @Valid @RequestBody CompleteEncounterRequest request) {
        return Result.success(service.complete(users.requireUserId(jwt), encounterId, request.version()));
    }
}
