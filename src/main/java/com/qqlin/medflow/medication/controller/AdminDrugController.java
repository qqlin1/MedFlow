package com.qqlin.medflow.medication.controller;

import com.qqlin.medflow.medication.domain.Drug;
import com.qqlin.medflow.medication.domain.DrugStatus;
import com.qqlin.medflow.medication.dto.*;
import com.qqlin.medflow.medication.service.DrugService;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/drugs")
public class AdminDrugController {
    private final DrugService service;
    public AdminDrugController(DrugService service) { this.service = service; }
    @GetMapping
    public Result<DrugPageResponse> list(@RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) DrugStatus status,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int size) {
        return Result.success(service.adminPage(keyword, status, page, size));
    }
    @PostMapping
    public Result<Drug> create(@Valid @RequestBody CreateDrugRequest request) {
        return Result.success(service.create(request));
    }
    @PutMapping("/{id}")
    public Result<Drug> update(@PathVariable @Positive long id, @Valid @RequestBody UpdateDrugRequest request) {
        return Result.success(service.update(id, request));
    }
}
