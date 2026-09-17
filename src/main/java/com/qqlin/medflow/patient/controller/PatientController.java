package com.qqlin.medflow.patient.controller;

import com.qqlin.medflow.patient.domain.CreatePatientCommand;
import com.qqlin.medflow.patient.dto.CreatePatientRequest;
import com.qqlin.medflow.patient.dto.PatientResponse;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patientService;
    private final JwtCurrentUserResolver currentUserResolver;

    public PatientController(
            PatientService patientService,
            JwtCurrentUserResolver currentUserResolver
    ) {
        this.patientService = patientService;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping
    public Result<PatientResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePatientRequest request
    ) {
        return Result.success(
                PatientResponse.from(
                        patientService.create(
                                currentUserResolver.requireUserId(jwt),
                                new CreatePatientCommand(
                                        request.name(),
                                        request.gender(),
                                        request.birthDate(),
                                        request.phone(),
                                        request.relationship()
                                )
                        )
                )
        );
    }

    @GetMapping
    public Result<List<PatientResponse>> findMine(
            @AuthenticationPrincipal Jwt jwt
    ) {
        List<PatientResponse> patients = patientService
                .findOwnedPatients(
                        currentUserResolver.requireUserId(jwt)
                )
                .stream()
                .map(PatientResponse::from)
                .toList();

        return Result.success(patients);
    }

    @GetMapping("/{patientId}")
    public Result<PatientResponse> findMineById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "就诊人 ID 必须大于 0")
            long patientId
    ) {
        return Result.success(
                PatientResponse.from(
                        patientService.getOwnedPatient(
                                currentUserResolver.requireUserId(jwt),
                                patientId
                        )
                )
        );
    }

}
