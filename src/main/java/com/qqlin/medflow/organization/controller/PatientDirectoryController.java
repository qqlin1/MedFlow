package com.qqlin.medflow.organization.controller;

import com.qqlin.medflow.organization.service.OrganizationService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/patient")
public class PatientDirectoryController {
    private final OrganizationService service;
    public PatientDirectoryController(OrganizationService service) { this.service = service; }
    public record DepartmentItem(long id, String name) {}
    public record DoctorItem(long id, long departmentId, String name) {}
    @GetMapping("/departments")
    public Result<List<DepartmentItem>> departments() {
        return Result.success(service.findEnabledDepartments().stream()
                .map(d -> new DepartmentItem(d.id(), d.name())).toList());
    }
    @GetMapping("/doctors")
    public Result<List<DoctorItem>> doctors(@RequestParam @Positive long departmentId) {
        return Result.success(service.findEnabledDoctorsByDepartmentId(departmentId).stream()
                .map(d -> new DoctorItem(d.id(), d.departmentId(), d.name())).toList());
    }
}
