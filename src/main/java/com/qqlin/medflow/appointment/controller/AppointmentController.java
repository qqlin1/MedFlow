package com.qqlin.medflow.appointment.controller;

import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.dto.ConfirmAppointmentResponse;
import com.qqlin.medflow.appointment.dto.CreateAppointmentRequest;
import com.qqlin.medflow.appointment.dto.CreateAppointmentResponse;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final JwtCurrentUserResolver currentUserResolver;

    public AppointmentController(
            AppointmentService appointmentService,
            JwtCurrentUserResolver currentUserResolver
    ) {
        this.appointmentService = appointmentService;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping
    public Result<CreateAppointmentResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key 不能为空")
            @Size(max = 64, message = "Idempotency-Key 不能超过 64 个字符")
            @Pattern(
                    regexp = "^[A-Za-z0-9._:-]+$",
                    message = "Idempotency-Key 格式不正确"
            )
            String idempotencyKey,
            @Valid @RequestBody CreateAppointmentRequest request
    ) {
        return Result.success(
                CreateAppointmentResponse.from(
                        appointmentService.create(
                                currentUserResolver.requireUserId(jwt),
                                new CreateAppointmentCommand(
                                        request.patientId(),
                                        request.slotId(),
                                        idempotencyKey
                                )
                        )
                )
        );
    }

    @PostMapping("/{appointmentId}/confirm")
    public Result<ConfirmAppointmentResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable
            @Positive(message = "预约 ID 必须大于 0")
            long appointmentId
    ) {
        return Result.success(
                ConfirmAppointmentResponse.from(
                        appointmentService.confirm(
                                currentUserResolver.requireUserId(jwt),
                                appointmentId
                        )
                )
        );
    }
}
