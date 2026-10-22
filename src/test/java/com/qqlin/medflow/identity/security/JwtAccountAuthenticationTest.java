package com.qqlin.medflow.identity.security;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real signed Bearer tokens pass through the actual decoder and filter chain. */
@WebMvcTest(
        controllers = JwtAccountAuthenticationTest.IdentityProbeController.class,
        properties = {
                "medflow.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "medflow.security.jwt.issuer=medflow-auth-test",
                "medflow.security.jwt.access-token-ttl=PT30M"
        }
)
@Import({SecurityConfig.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, JwtAccountAuthenticationTest.IdentityProbeController.class})
class JwtAccountAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SecretKey secretKey;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private DbUserDetailsService dbUserDetailsService;

    @Test
    void shouldAcceptCurrentTokenThenRejectItAfterVersionIncrement() throws Exception {
        UserAccount account = account(UserStatus.ENABLED, 3);
        when(userRepository.findById(101L)).thenReturn(Optional.of(account));
        String token = jwtService.issue(account);

        mockMvc.perform(get("/api/v1/security-probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("101"));

        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.ENABLED, 4)));
        expectUnauthorized(token);
    }

    @Test
    void shouldRejectTokenWhenAccountHasBeenDisabled() throws Exception {
        String token = jwtService.issue(account(UserStatus.ENABLED, 3));
        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.DISABLED, 3)));
        expectUnauthorized(token);
    }

    @Test
    void shouldRejectTokenWhenAccountHasBeenLocked() throws Exception {
        String token = jwtService.issue(account(UserStatus.ENABLED, 3));
        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.LOCKED, 3)));
        expectUnauthorized(token);
    }

    @Test
    void shouldRejectTokenWhenAccountHasBeenDeleted() throws Exception {
        when(userRepository.findById(101L)).thenReturn(Optional.empty());
        expectUnauthorized(jwtService.issue(account(UserStatus.ENABLED, 3)));
    }

    @Test
    void shouldRejectOldRoleEvenWithoutVersionIncrement() throws Exception {
        UserAccount formerAdmin = new UserAccount(101L, "patient-user", "unused",
                UserRole.ADMIN, UserStatus.ENABLED, 3);
        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.ENABLED, 3)));

        mockMvc.perform(get("/api/v1/admin/security-probe")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.issue(formerAdmin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldRejectSignedFractionalIdentityClaimBeforeAccountLookup() throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .issuer("medflow-auth-test").subject("patient-user")
                .claim("uid", 101.5d).claim("role", "PATIENT")
                .claim("tokenVersion", 3)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .signWith(secretKey).compact();

        expectUnauthorized(token);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldKeepIssuerValidationWhenAddingAccountValidation() throws Exception {
        Instant now = Instant.now();
        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.ENABLED, 3)));
        String token = Jwts.builder()
                .issuer("other-issuer").subject("patient-user")
                .claim("uid", 101L).claim("role", "PATIENT")
                .claim("tokenVersion", 3)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                .signWith(secretKey).compact();

        expectUnauthorized(token);
    }

    @Test
    void shouldKeepExpirationValidationWhenAddingAccountValidation() throws Exception {
        Instant now = Instant.now();
        when(userRepository.findById(101L))
                .thenReturn(Optional.of(account(UserStatus.ENABLED, 3)));
        String token = Jwts.builder()
                .issuer("medflow-auth-test").subject("patient-user")
                .claim("uid", 101L).claim("role", "PATIENT")
                .claim("tokenVersion", 3)
                .issuedAt(Date.from(now.minusSeconds(600)))
                .expiration(Date.from(now.minusSeconds(300)))
                .signWith(secretKey).compact();

        expectUnauthorized(token);
    }

    private void expectUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/v1/security-probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private UserAccount account(UserStatus status, int version) {
        return new UserAccount(101L, "patient-user", "unused-password-hash",
                UserRole.PATIENT, status, version);
    }

    @RestController
    static class IdentityProbeController {
        @GetMapping({"/api/v1/security-probe", "/api/v1/admin/security-probe"})
        String probe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getClaim("uid").toString();
        }
    }
}
