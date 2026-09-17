package com.qqlin.medflow.organization.controller;

import com.qqlin.medflow.organization.domain.CreateDoctorCommand;
import com.qqlin.medflow.organization.dto.ClinicRoomResponse;
import com.qqlin.medflow.organization.dto.CreateClinicRoomRequest;
import com.qqlin.medflow.organization.dto.CreateDepartmentRequest;
import com.qqlin.medflow.organization.dto.CreateDoctorRequest;
import com.qqlin.medflow.organization.dto.DepartmentResponse;
import com.qqlin.medflow.organization.dto.DoctorResponse;
import com.qqlin.medflow.organization.service.OrganizationService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrganizationController {

    private final OrganizationService organizationService;

    public AdminOrganizationController(
            OrganizationService organizationService
    ) {
        this.organizationService = organizationService;
    }

    @PostMapping("/departments")
    public Result<DepartmentResponse> createDepartment(
            @Valid @RequestBody CreateDepartmentRequest request
    ) {
        return Result.success(
                DepartmentResponse.from(
                        organizationService.createDepartment(
                                request.name()
                        )
                )
        );
    }

    @GetMapping("/departments")
    public Result<List<DepartmentResponse>> findEnabledDepartments() {
        return Result.success(
                organizationService.findEnabledDepartments()
                        .stream()
                        .map(DepartmentResponse::from)
                        .toList()
        );
    }

    @PostMapping("/clinic-rooms")
    public Result<ClinicRoomResponse> createClinicRoom(
            @Valid @RequestBody CreateClinicRoomRequest request
    ) {
        return Result.success(
                ClinicRoomResponse.from(
                        organizationService.createClinicRoom(
                                request.departmentId(),
                                request.name()
                        )
                )
        );
    }

    @GetMapping("/clinic-rooms")
    public Result<List<ClinicRoomResponse>> findEnabledClinicRooms(
            @RequestParam @Positive(
                    message = "科室 ID 必须大于0"
            )
            long departmentId
    ) {
        return Result.success(
                organizationService
                        .findEnabledClinicRoomsByDepartmentId(
                                departmentId
                        )
                        .stream()
                        .map(ClinicRoomResponse::from)
                        .toList()
        );
    }

    @PostMapping("/doctors")
    public Result<DoctorResponse> bindDoctor(
            @Valid @RequestBody CreateDoctorRequest request
    ) {
        return Result.success(
                DoctorResponse.from(
                        organizationService.bindDoctor(
                                new CreateDoctorCommand(
                                        request.username(),
                                        request.departmentId(),
                                        request.name()
                                )
                        )
                )
        );
    }

    @GetMapping("/doctors")
    public Result<List<DoctorResponse>> findEnabledDoctors(
            @RequestParam @Positive(
                    message = "科室 ID 必须大于0"
            )
            long departmentId
    ) {
        return Result.success(
                organizationService
                        .findEnabledDoctorsByDepartmentId(
                                departmentId
                        )
                        .stream()
                        .map(DoctorResponse::from)
                        .toList()
        );
    }
}
