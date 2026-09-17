package com.qqlin.medflow.appointment.controller;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.security.DbUserDetailsService;
import com.qqlin.medflow.identity.security.JwtCurrentUserResolver;
import com.qqlin.medflow.identity.security.JwtService;
import com.qqlin.medflow.identity.security.RestAccessDeniedHandler;
import com.qqlin.medflow.identity.security.RestAuthenticationEntryPoint;
import com.qqlin.medflow.identity.security.SecurityConfig;
import com.qqlin.medflow.identity.security.SecurityErrorResponseWriter;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer checks with the real JWT decoder and authorization rules.
 * The AppointmentService stays mocked, so database transactions and MySQL
 * locking are deliberately outside this test class's proof boundary.
 */
@WebMvcTest(
        controllers = AppointmentController.class,
        properties = {
                "medflow.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "medflow.security.jwt.issuer=medflow-controller-test",
                "medflow.security.jwt.access-token-ttl=PT30M"
        }
)
@Import({
        SecurityConfig.class,
        JwtCurrentUserResolver.class,
        JwtService.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        GlobalExceptionHandler.class
})
class AppointmentControllerTest {

    private static final long PATIENT_USER_ID = 101L;
    private static final long ADMIN_USER_ID = 202L;
    private static final long APPOINTMENT_ID = 303L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private DbUserDetailsService dbUserDetailsService;

    @Test
    void confirmShouldReturnBookedAppointmentAndUseUserIdFromJwt()
            throws Exception {
        Appointment booked = appointment(AppointmentStatus.BOOKED);
        when(appointmentService.confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        )).thenReturn(booked);

        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        APPOINTMENT_ID
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                bearerToken(
                                        PATIENT_USER_ID,
                                        UserRole.PATIENT
                                )
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath(
                        "$.data.appointmentId"
                ).value(APPOINTMENT_ID))
                .andExpect(jsonPath("$.data.status").value("BOOKED"));

        verify(appointmentService).confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        );
    }

    @Test
    void confirmWithoutTokenShouldReturn401BeforeCallingService()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        APPOINTMENT_ID
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirmWithAdminTokenShouldReturn403BeforeCallingService()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        APPOINTMENT_ID
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                bearerToken(
                                        ADMIN_USER_ID,
                                        UserRole.ADMIN
                                )
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirmWithZeroAppointmentIdShouldReturn400()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        0
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code"
                ).value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirmWithNegativeAppointmentIdShouldReturn400()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        -1
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code"
                ).value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirmWithNonNumericAppointmentIdShouldReturn400()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        "not-a-number"
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code"
                ).value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirmShouldTranslateBusinessNotFoundTo404()
            throws Exception {
        when(appointmentService.confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        )).thenThrow(new BusinessException(
                ErrorCode.APPOINTMENT_NOT_FOUND
        ));

        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        APPOINTMENT_ID
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath(
                        "$.code"
                ).value("APPOINTMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(appointmentService).confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        );
    }

    @Test
    void confirmShouldTranslateBusinessConflictTo409()
            throws Exception {
        when(appointmentService.confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        )).thenThrow(new BusinessException(
                ErrorCode.APPOINTMENT_NOT_CONFIRMABLE
        ));

        mockMvc.perform(post(
                        "/api/v1/appointments/{appointmentId}/confirm",
                        APPOINTMENT_ID
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.code"
                ).value("APPOINTMENT_NOT_CONFIRMABLE"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(appointmentService).confirm(
                PATIENT_USER_ID,
                APPOINTMENT_ID
        );
    }

    @Test
    void createWithoutIdempotencyKeyShouldReturn400BeforeCallingService()
            throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code"
                ).value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void createWithMalformedIdempotencyKeyShouldReturn400BeforeCallingService()
            throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                patientBearerToken()
                        )
                        .header("Idempotency-Key", "contains a space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRequestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.code"
                ).value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    private String patientBearerToken() {
        return bearerToken(PATIENT_USER_ID, UserRole.PATIENT);
    }

    private String bearerToken(long userId, UserRole role) {
        UserAccount account = new UserAccount(
                userId,
                role.name().toLowerCase() + "-user",
                "unused-password-hash",
                role,
                UserStatus.ENABLED,
                0
        );

        return "Bearer " + jwtService.issue(account);
    }

    private Appointment appointment(AppointmentStatus status) {
        LocalDateTime now = LocalDateTime.now();

        return new Appointment(
                APPOINTMENT_ID,
                404L,
                505L,
                status,
                now.plusMinutes(10),
                now.plusHours(1)
        );
    }

    private String validCreateRequestBody() {
        return """
                {
                  "patientId": 404,
                  "slotId": 505
                }
                """;
    }
}
