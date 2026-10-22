package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients/{patientId}/history")
public class PatientHistoryController {
    private final PatientHistoryService history;
    private final JwtCurrentUserResolver users;
    public PatientHistoryController(PatientHistoryService history,JwtCurrentUserResolver users) {this.history=history;this.users=users;}

    @GetMapping
    public Result<HistoryPage> page(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive long patientId,
            @RequestParam(defaultValue="1") @Positive int page,@RequestParam(defaultValue="20") @Positive @Max(50) int size) {
        return Result.success(history.patientPage(users.requireUserId(jwt),patientId,page,size));
    }

    @GetMapping("/{appointmentId}")
    public Result<HistoryDetail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive long patientId,
            @PathVariable @Positive long appointmentId) {
        return Result.success(history.patientDetail(users.requireUserId(jwt),patientId,appointmentId));
    }
}
