package com.qqlin.medflow.organization.provisioning;

import com.qqlin.medflow.organization.dto.DoctorResponse;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/doctor-accounts")
public class DoctorAccountController {
    private final DoctorAccountService service;
    public DoctorAccountController(DoctorAccountService service) { this.service = service; }
    public record CreateRequest(@NotBlank @Size(max=64) String username,
                                @NotBlank @Size(min=8,max=72) String password,
                                @NotBlank @Size(max=64) String name, @Positive long departmentId) {}
    @PostMapping
    public Result<DoctorResponse> create(@Valid @RequestBody CreateRequest request) {
        return Result.success(DoctorResponse.from(service.create(
                request.username(), request.password(), request.name(), request.departmentId())));
    }
}
