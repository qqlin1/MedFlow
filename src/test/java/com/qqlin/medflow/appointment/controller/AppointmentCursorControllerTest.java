package com.qqlin.medflow.appointment.controller;

import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AppointmentController.class, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=medflow-cursor-test",
        "medflow.security.jwt.access-token-ttl=PT30M"
})
@Import({SecurityConfig.class, JwtCurrentUserResolver.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class AppointmentCursorControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private AppointmentService appointmentService;
    @MockitoBean private DbUserDetailsService userDetailsService;
    @MockitoBean private UserRepository userRepository;

    @Test
    void firstRequestUsesJwtOwnerAndHasNoTotal() throws Exception {
        when(appointmentService.findOwnedSlice(101L, 20, null))
                .thenReturn(new AppointmentSlice(List.of(), false, null, 20));
        mockMvc.perform(get("/api/v1/appointments/cursor")
                        .header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.total").doesNotExist());
        verify(appointmentService).findOwnedSlice(101L, 20, null);
    }

    @Test
    void nextRequestPreservesExactMillisecondBoundary() throws Exception {
        AppointmentCursor cursor = new AppointmentCursor(
                LocalDateTime.parse("2026-10-02T10:00:00.123"), 150);
        when(appointmentService.findOwnedSlice(101L, 50, cursor))
                .thenReturn(new AppointmentSlice(List.of(), false, null, 50));
        mockMvc.perform(get("/api/v1/appointments/cursor")
                        .param("size", "50")
                        .param("afterCreatedAt", "2026-10-02T10:00:00.123")
                        .param("afterId", "150")
                        .header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isOk());
        verify(appointmentService).findOwnedSlice(101L, 50, cursor);
    }

    @Test
    void incompleteOrMalformedCursorAndInvalidSizeReturn400() throws Exception {
        String bearer = token(UserRole.PATIENT);
        for (String[] parameter : new String[][]{
                {"afterId", "150"},
                {"afterCreatedAt", "2026-10-02T10:00:00.123"},
                {"afterCreatedAt", "not-a-time"},
                {"size", "0"}, {"size", "51"}}) {
            mockMvc.perform(get("/api/v1/appointments/cursor")
                            .param(parameter[0], parameter[1])
                            .header("Authorization", bearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/api/v1/appointments/cursor")
                        .param("afterCreatedAt", "2026-10-02T10:00:00.123")
                        .param("afterId", "0").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void unauthenticatedAndWrongRoleRequestsNeverReachService() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/cursor"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/appointments/cursor")
                        .header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    private String token(UserRole role) {
        UserAccount account = new UserAccount(101L, "cursor-user", "unused",
                role, UserStatus.ENABLED, 0);
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));
        return "Bearer " + jwtService.issue(account);
    }
}
