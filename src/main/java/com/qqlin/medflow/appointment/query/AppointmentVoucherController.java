package com.qqlin.medflow.appointment.query;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.web.Result;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppointmentVoucherController {
    private final AppointmentVoucherService vouchers;
    private final JwtCurrentUserResolver users;

    public AppointmentVoucherController(AppointmentVoucherService vouchers, JwtCurrentUserResolver users) {
        this.vouchers = vouchers;
        this.users = users;
    }

    @GetMapping("/api/v1/appointments/{id}/voucher")
    public Result<AppointmentVoucher> get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return Result.success(vouchers.get(users.requireUserId(jwt), id));
    }
}
