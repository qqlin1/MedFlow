package com.qqlin.medflow.appointment.query;

import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import com.qqlin.medflow.shared.web.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;

@RestController
public class AppointmentVisitInfoController {
    private final AppointmentVisitInfoMapper mapper;
    private final JwtCurrentUserResolver users;
    public AppointmentVisitInfoController(AppointmentVisitInfoMapper mapper, JwtCurrentUserResolver users) {
        this.mapper = mapper; this.users = users;
    }
    @GetMapping("/api/v1/appointments/visit-summaries")
    public Result<List<AppointmentVisitInfo>> summaries(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestParam String ids) {
        String[] values = ids.split(",", -1);
        if (values.length > 50) throw invalidIds();
        var unique = new LinkedHashSet<Long>();
        for (String value : values) {
            String token = value.trim();
            if (!token.matches("[0-9]{1,19}")) throw invalidIds();
            try {
                long id = Long.parseLong(token);
                if (id <= 0) throw invalidIds();
                unique.add(id);
            } catch (NumberFormatException exception) {
                throw invalidIds();
            }
        }
        return Result.success(mapper.findOwnedBatch(List.copyOf(unique), users.requireUserId(jwt)));
    }

    private BusinessException invalidIds() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "ids须包含1至50个正整数预约编号");
    }

    @GetMapping("/api/v1/appointments/{id}/visit-info")
    public Result<AppointmentVisitInfo> info(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        var result = mapper.findOwned(id, users.requireUserId(jwt));
        if (result == null) throw new BusinessException(ErrorCode.APPOINTMENT_NOT_FOUND);
        return Result.success(result);
    }
}
