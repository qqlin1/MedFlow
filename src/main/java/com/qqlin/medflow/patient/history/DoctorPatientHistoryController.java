package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/doctor/appointments/{anchorAppointmentId}/patient-history")
public class DoctorPatientHistoryController {
    private final PatientHistoryService history;
    private final JwtCurrentUserResolver users;
    public DoctorPatientHistoryController(PatientHistoryService history,JwtCurrentUserResolver users) {this.history=history;this.users=users;}

    @GetMapping
    public Result<HistoryPage> page(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive long anchorAppointmentId,
            @RequestParam(defaultValue="1") @Positive int page,@RequestParam(defaultValue="20") @Positive @Max(50) int size) {
        return Result.success(history.doctorPage(users.requireUserId(jwt),anchorAppointmentId,page,size));
    }

    @GetMapping("/{appointmentId}")
    public Result<HistoryDetail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive long anchorAppointmentId,
            @PathVariable @Positive long appointmentId) {
        return Result.success(history.doctorDetail(users.requireUserId(jwt),anchorAppointmentId,appointmentId));
    }
}
