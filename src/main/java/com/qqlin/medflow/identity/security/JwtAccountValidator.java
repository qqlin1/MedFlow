package com.qqlin.medflow.identity.security;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigInteger;

/**
 * A valid signature proves who issued a token, not that the account is still
 * allowed to use it. Reload the current account before granting its authority.
 */
public final class JwtAccountValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_ACCOUNT = new OAuth2Error(
            "invalid_token", "Token account is no longer valid", null
    );

    private final UserRepository userRepository;

    public JwtAccountValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long userId = exactLong(jwt.getClaim("uid"));
        Long tokenVersion = exactLong(jwt.getClaim("tokenVersion"));
        Object subject = jwt.getClaims().get("sub");
        Object role = jwt.getClaim("role");

        if (userId == null || userId <= 0
                || tokenVersion == null || tokenVersion < 0
                || tokenVersion > Integer.MAX_VALUE
                || !(subject instanceof String)
                || !(role instanceof String)) {
            return invalid();
        }

        UserAccount account = userRepository.findById(userId).orElse(null);
        if (account == null
                || account.status() != UserStatus.ENABLED
                || account.tokenVersion() != tokenVersion.intValue()
                || !account.username().equals(subject)
                || !account.role().name().equals(role)) {
            return invalid();
        }

        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(INVALID_ACCOUNT);
    }

    // Never truncate fractional claims or wrap an overflowing integer.
    private static Long exactLong(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        return null;
    }
}
