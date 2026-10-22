package com.qqlin.medflow.appointment.controller;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentDetail;
import com.qqlin.medflow.appointment.domain.AppointmentEventType;
import com.qqlin.medflow.appointment.domain.AppointmentPage;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.appointment.service.AppointmentService;
import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Query HTTP contract with the real JWT decoder and role rules. The service is
 * mocked; owner filtering and SQL results require separate service/MySQL tests.
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
class AppointmentQueryControllerTest {

    private static final long PATIENT_USER_ID = 101L;
    private static final long ADMIN_USER_ID = 202L;
    private static final long APPOINTMENT_ID = 303L;
    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 9, 22, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private DbUserDetailsService dbUserDetailsService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void detailShouldReturnOwnedAppointmentAndOrderedHistory() throws Exception {
        AppointmentDetail detail = new AppointmentDetail(
                appointment(APPOINTMENT_ID, AppointmentStatus.BOOKED),
                List.of(
                        new AppointmentStatusChange(
                                1L,
                                null,
                                AppointmentStatus.PENDING_CONFIRMATION,
                                AppointmentEventType.CREATED,
                                PATIENT_USER_ID,
                                CREATED_AT
                        ),
                        new AppointmentStatusChange(
                                2L,
                                AppointmentStatus.PENDING_CONFIRMATION,
                                AppointmentStatus.BOOKED,
                                AppointmentEventType.CONFIRMED,
                                PATIENT_USER_ID,
                                CREATED_AT.plusMinutes(1)
                        )
                )
        );
        when(appointmentService.getOwnedDetail(
                PATIENT_USER_ID, APPOINTMENT_ID
        )).thenReturn(detail);

        mockMvc.perform(get("/api/v1/appointments/{id}", APPOINTMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, patientToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.appointment.appointmentId")
                        .value(APPOINTMENT_ID))
                .andExpect(jsonPath("$.data.appointment.status")
                        .value("BOOKED"))
                .andExpect(jsonPath("$.data.history.length()")
                        .value(2))
                .andExpect(jsonPath("$.data.history[0].fromStatus")
                        .value(nullValue()))
                .andExpect(jsonPath("$.data.history[0].eventType")
                        .value("CREATED"))
                .andExpect(jsonPath("$.data.history[1].toStatus")
                        .value("BOOKED"))
                .andExpect(jsonPath("$.data.history[1].eventType")
                        .value("CONFIRMED"));

        verify(appointmentService).getOwnedDetail(
                PATIENT_USER_ID, APPOINTMENT_ID
        );
    }

    @Test
    void detailShouldTranslateMissingOrUnownedAppointmentTo404()
            throws Exception {
        when(appointmentService.getOwnedDetail(
                PATIENT_USER_ID, APPOINTMENT_ID
        )).thenThrow(new BusinessException(
                ErrorCode.APPOINTMENT_NOT_FOUND
        ));

        mockMvc.perform(get("/api/v1/appointments/{id}", APPOINTMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, patientToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("APPOINTMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void detailWithoutTokenShouldReturn401BeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/{id}", APPOINTMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void detailWithAdminTokenShouldReturn403BeforeService()
            throws Exception {
        mockMvc.perform(get("/api/v1/appointments/{id}", APPOINTMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION,
                                token(ADMIN_USER_ID, UserRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void detailWithInvalidIdShouldReturn400BeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/{id}", 0)
                        .header(HttpHeaders.AUTHORIZATION, patientToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_FAILED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void listShouldReturnRequestedPageAndUseJwtOwner() throws Exception {
        AppointmentPage page = new AppointmentPage(
                List.of(appointment(APPOINTMENT_ID, AppointmentStatus.CANCELLED)),
                3L,
                2,
                2
        );
        when(appointmentService.findOwnedPage(
                PATIENT_USER_ID, 2, 2
        )).thenReturn(page);

        mockMvc.perform(get("/api/v1/appointments")
                        .param("page", "2")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, patientToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.items.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.items[0].appointmentId")
                        .value(APPOINTMENT_ID))
                .andExpect(jsonPath("$.data.items[0].status")
                        .value("CANCELLED"));

        verify(appointmentService).findOwnedPage(
                PATIENT_USER_ID, 2, 2
        );
    }

    @Test
    void listShouldUseFirstPageDefaults() throws Exception {
        when(appointmentService.findOwnedPage(
                PATIENT_USER_ID, 1, 20
        )).thenReturn(new AppointmentPage(List.of(), 0L, 1, 20));

        mockMvc.perform(get("/api/v1/appointments")
                        .header(HttpHeaders.AUTHORIZATION, patientToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()")
                        .value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(appointmentService).findOwnedPage(
                PATIENT_USER_ID, 1, 20
        );
    }

    @Test
    void listShouldRejectInvalidBoundsBeforeService() throws Exception {
        for (String[] parameters : new String[][] {
                {"0", "20"},
                {"1", "0"},
                {"1", "51"}
        }) {
            mockMvc.perform(get("/api/v1/appointments")
                            .param("page", parameters[0])
                            .param("size", parameters[1])
                            .header(HttpHeaders.AUTHORIZATION, patientToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("VALIDATION_FAILED"));
        }

        verifyNoInteractions(appointmentService);
    }

    @Test
    void listWithoutTokenShouldReturn401BeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/appointments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(appointmentService);
    }

    @Test
    void listWithAdminTokenShouldReturn403BeforeService()
            throws Exception {
        mockMvc.perform(get("/api/v1/appointments")
                        .header(HttpHeaders.AUTHORIZATION,
                                token(ADMIN_USER_ID, UserRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(appointmentService);
    }

    private String patientToken() {
        return token(PATIENT_USER_ID, UserRole.PATIENT);
    }

    private String token(long userId, UserRole role) {
        UserAccount account = new UserAccount(
                userId,
                role.name().toLowerCase() + "-user",
                "unused-password-hash",
                role,
                UserStatus.ENABLED,
                0
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(account));
        return "Bearer " + jwtService.issue(account);
    }

    private Appointment appointment(long id, AppointmentStatus status) {
        return new Appointment(
                id,
                404L,
                505L,
                status,
                CREATED_AT.plusMinutes(15),
                CREATED_AT.plusHours(1)
        );
    }
}
