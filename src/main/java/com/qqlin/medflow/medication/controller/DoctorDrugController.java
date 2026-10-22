package com.qqlin.medflow.medication.controller;

import com.qqlin.medflow.medication.dto.DrugPageResponse;
import com.qqlin.medflow.medication.service.DrugService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/doctor/drugs")
public class DoctorDrugController {
    private final DrugService service;
    public DoctorDrugController(DrugService service) { this.service = service; }
    @GetMapping
    public Result<DrugPageResponse> list(@RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.doctorPage(keyword, page, size));
    }
}
