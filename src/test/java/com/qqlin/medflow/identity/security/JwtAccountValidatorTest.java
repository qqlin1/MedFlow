package com.qqlin.medflow.identity.security;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAccountValidatorTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAccountValidator validator = new JwtAccountValidator(userRepository);

    @Test
    void shouldAcceptOnlyCurrentEnabledAccountAndMatchingIdentity() {
        when(userRepository.findById(101L)).thenReturn(Optional.of(account()));

        assertFalse(validator.validate(token(101L, 3, "patient-user", "PATIENT"))
                .hasErrors());
    }

    @ParameterizedTest
    @MethodSource("invalidUserIds")
    void shouldRejectMalformedUserIdWithoutLookingUpAccount(Object userId) {
        assertTrue(validator.validate(token(userId, 3, "patient-user", "PATIENT"))
                .hasErrors());
        verifyNoInteractions(userRepository);
    }

    static Stream<Object> invalidUserIds() {
        return Stream.of(null, 0L, -1L, "101", 101.5d,
                new BigInteger("9223372036854775808"), List.of(101L));
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void shouldRejectMalformedVersionWithoutLookingUpAccount(Object version) {
        assertTrue(validator.validate(token(101L, version, "patient-user", "PATIENT"))
                .hasErrors());
        verifyNoInteractions(userRepository);
    }

    static Stream<Object> invalidVersions() {
        return Stream.of(null, -1, "3", 3.5d, 2147483648L, List.of(3));
    }

    @Test
    void shouldRejectMissingOrStructuredIdentityClaims() {
        for (Jwt jwt : List.of(
                token(101L, 3, null, "PATIENT"),
                token(101L, 3, List.of("patient-user"), "PATIENT"),
                token(101L, 3, "patient-user", null),
                token(101L, 3, "patient-user", List.of("PATIENT", "ADMIN"))
        )) {
            assertTrue(validator.validate(jwt).hasErrors());
        }
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldRejectChangedSubjectOrRoleEvenWhenVersionMatches() {
        when(userRepository.findById(101L)).thenReturn(Optional.of(account()));

        assertTrue(validator.validate(token(101L, 3, "other-user", "PATIENT"))
                .hasErrors());
        assertTrue(validator.validate(token(101L, 3, "patient-user", "ADMIN"))
                .hasErrors());
    }

    private UserAccount account() {
        return new UserAccount(101L, "patient-user", "unused-password-hash",
                UserRole.PATIENT, UserStatus.ENABLED, 3);
    }

    private Jwt token(Object userId, Object version, Object subject, Object role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("unused-token")
                .header("alg", "HS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claims(claims -> {
                    if (userId != null) claims.put("uid", userId);
                    if (version != null) claims.put("tokenVersion", version);
                    if (subject != null) claims.put("sub", subject);
                    if (role != null) claims.put("role", role);
                })
                .build();
    }
}
