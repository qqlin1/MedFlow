package com.qqlin.medflow.appointment.query;

import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FollowUpSourceController {
    private final AppointmentService appointments;
    private final JwtCurrentUserResolver users;

    public FollowUpSourceController(AppointmentService appointments, JwtCurrentUserResolver users) {
        this.appointments=appointments;
        this.users=users;
    }

    @GetMapping("/api/v1/patient/follow-up-sources/{encounterId}")
    public Result<FollowUpSource> source(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message="来源接诊 ID 必须大于 0") long encounterId) {
        return Result.success(appointments.getOwnedFollowUpSource(users.requireUserId(jwt),encounterId));
    }
}
