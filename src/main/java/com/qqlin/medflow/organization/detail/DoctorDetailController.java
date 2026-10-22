package com.qqlin.medflow.organization.detail;

import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
public class DoctorDetailController {
    private final DoctorDetailService service;
    public DoctorDetailController(DoctorDetailService service) { this.service=service; }
    public record UpdateProfileRequest(@NotBlank @Size(max=64) String name,
            @Size(max=64) String title,@Size(max=1000) String specialty,@Size(max=4000) String introduction) {
        public UpdateProfileRequest(String name) { this(name,null,null,null); }
    }

    @GetMapping("/api/v1/patient/doctor-directory")
    public Result<java.util.List<DoctorDetail>> directory(@RequestParam(required=false) @Positive Long departmentId,
            @RequestParam(defaultValue="") @Size(max=64) String query) {
        return Result.success(service.directory(departmentId,query));
    }

    @GetMapping("/api/v1/patient/doctors/{id}")
    public Result<DoctorDetail> detail(@PathVariable @Positive long id) { return Result.success(service.get(id)); }

    @GetMapping("/api/v1/admin/doctors/{id}/profile")
    public Result<DoctorDetail> adminProfile(@PathVariable @Positive long id) { return Result.success(service.get(id)); }

    @PutMapping("/api/v1/admin/doctors/{id}/profile")
    public Result<DoctorDetail> update(@PathVariable @Positive long id,@RequestBody @Valid UpdateProfileRequest request) {
        return Result.success(service.updateProfile(id,request.name(),request.title(),request.specialty(),request.introduction()));
    }
}
