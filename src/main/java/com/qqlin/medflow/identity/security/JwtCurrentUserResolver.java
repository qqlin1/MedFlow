package com.qqlin.medflow.identity.security;

import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Extracts the stable system-user id from an already verified JWT.
 * Controllers pass the resulting id into Services; Services do not read the
 * SecurityContext directly, which keeps their business rules easier to test.
 */
@Component
public class JwtCurrentUserResolver {

    public long requireUserId(Jwt jwt) {
        Object userIdClaim = jwt == null
                ? null
                : jwt.getClaim("uid");

        if (!(userIdClaim instanceof Number userId)
                || userId.longValue() <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return userId.longValue();
    }
}
