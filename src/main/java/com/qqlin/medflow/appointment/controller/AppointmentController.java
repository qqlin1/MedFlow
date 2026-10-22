package com.qqlin.medflow.appointment.controller;

import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.dto.CancelAppointmentResponse;
import com.qqlin.medflow.appointment.dto.AppointmentDetailResponse;
import com.qqlin.medflow.appointment.dto.AppointmentPageResponse;
import com.qqlin.medflow.appointment.dto.AppointmentSliceResponse;
import com.qqlin.medflow.appointment.dto.ConfirmAppointmentResponse;
import com.qqlin.medflow.appointment.dto.CreateAppointmentRequest;
import com.qqlin.medflow.appointment.dto.CreateAppointmentResponse;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

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

    @GetMapping("/{appointmentId}")
    public Result<AppointmentDetailResponse> findMineById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable
            @Positive(message = "预约 ID 必须大于 0")
            long appointmentId
    ) {
        return Result.success(
                AppointmentDetailResponse.from(
                        appointmentService.getOwnedDetail(
                                currentUserResolver.requireUserId(jwt),
                                appointmentId
                        )
                )
        );
    }

    @GetMapping
    public Result<AppointmentPageResponse> findMine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1")
            @Positive(message = "页码必须大于 0") int page,
            @RequestParam(defaultValue = "20")
            @Positive(message = "每页数量必须大于 0")
            @Max(value = 50, message = "每页最多 50 条") int size
    ) {
        return Result.success(
                AppointmentPageResponse.from(
                        appointmentService.findOwnedPage(
                                currentUserResolver.requireUserId(jwt),
                                page,
                                size
                        )
                )
        );
    }

    @GetMapping("/cursor")
    public Result<AppointmentSliceResponse> findMineByCursor(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20")
            @Positive(message = "每页数量必须大于 0")
            @Max(value = 50, message = "每页最多 50 条") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime afterCreatedAt,
            @RequestParam(required = false)
            @Positive(message = "游标 ID 必须大于 0") Long afterId
    ) {
        if ((afterCreatedAt == null) != (afterId == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        AppointmentCursor cursor = afterCreatedAt == null ? null
                : new AppointmentCursor(afterCreatedAt, afterId);
        return Result.success(AppointmentSliceResponse.from(
                appointmentService.findOwnedSlice(
                        currentUserResolver.requireUserId(jwt), size, cursor)));
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
                                        idempotencyKey,
                                        request.sourceEncounterId()
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

    @PostMapping("/{appointmentId}/cancel")
    public Result<CancelAppointmentResponse> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable
            @Positive(message = "预约 ID 必须大于 0")
            long appointmentId
    ) {
        return Result.success(
                CancelAppointmentResponse.from(
                        appointmentService.cancel(
                                currentUserResolver.requireUserId(jwt),
                                appointmentId
                        )
                )
        );
    }
}
