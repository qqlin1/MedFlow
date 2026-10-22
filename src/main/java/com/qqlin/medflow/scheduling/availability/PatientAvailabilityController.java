package com.qqlin.medflow.scheduling.availability;

import com.qqlin.medflow.scheduling.availability.AvailabilityModels.*;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/patient")
public class PatientAvailabilityController {
    private final AvailabilityService service;
    public PatientAvailabilityController(AvailabilityService service) { this.service = service; }

    @GetMapping("/availability-days")
    public Result<List<AvailabilityDay>> days(@RequestParam(required = false) @Positive Long departmentId,
            @RequestParam(required = false) @Positive Long doctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "7") @Positive @Max(AvailabilityService.MAX_DAYS) int days) {
        return Result.success(service.days(departmentId, doctorId, from, days));
    }

    @GetMapping("/available-doctors")
    public Result<List<AvailableDoctor>> doctors(@RequestParam @Positive long departmentId,
            @RequestParam(required = false) @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate) {
        return Result.success(service.doctors(departmentId, workDate));
    }
}
