package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AdminScheduleManagementController.class, DoctorScheduleController.class},
        properties = {"medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "medflow.security.jwt.issuer=schedule-management-controller-test",
                "medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class, JwtCurrentUserResolver.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class ScheduleManagementControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @MockitoBean ScheduleManagementService service;
    @MockitoBean DbUserDetailsService userDetails;
    @MockitoBean UserRepository users;

    @Test void doctorSchedulesUseAuthenticatedUserAndIgnoreSpoofedDoctorId() throws Exception {
        when(service.findDoctorPage(77, null, null, null, 1, 20))
                .thenReturn(new Page<>(List.of(), 0, 1, 20));
        mvc.perform(get("/api/v1/doctor/schedules").param("doctorId", "999")
                        .header(HttpHeaders.AUTHORIZATION, token(77, UserRole.DOCTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(service).findDoctorPage(77, null, null, null, 1, 20);
    }

    @Test void doctorAppointmentsUseAuthenticatedUserAndRequestedDate() throws Exception {
        LocalDate date = LocalDate.now();
        when(service.doctorAppointments(77, date, 1, 20)).thenReturn(new Page<>(List.of(), 0, 1, 20));
        mvc.perform(get("/api/v1/doctor/appointments").param("workDate", date.toString())
                        .header(HttpHeaders.AUTHORIZATION, token(77, UserRole.DOCTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        verify(service).doctorAppointments(77, date, 1, 20);
    }

    @Test void patientCannotReadDoctorDirectoryOrAdministrativeSchedules() throws Exception {
        String token = token(44, UserRole.PATIENT);
        mvc.perform(get("/api/v1/doctor/appointments").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/schedules").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void doctorCannotCancelAdministrativeSchedule() throws Exception {
        mvc.perform(post("/api/v1/admin/schedules/1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, token(77, UserRole.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"请假\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void cancelUsesVerifiedAdminIdAndRejectsEmptyReason() throws Exception {
        String token = token(88, UserRole.ADMIN);
        mvc.perform(post("/api/v1/admin/schedules/1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"请假\",\"operatorId\":999}"))
                .andExpect(status().isOk());
        verify(service).cancel(1, 88, "请假");
        clearInvocations(service);
        mvc.perform(post("/api/v1/admin/schedules/1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidPageAndUnknownStatusFailBeforeService() throws Exception {
        String token = token(88, UserRole.ADMIN);
        mvc.perform(get("/api/v1/admin/schedules").param("size", "51")
                        .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/schedules").param("status", "WHATEVER")
                        .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedRequestFailsBeforeService() throws Exception {
        mvc.perform(get("/api/v1/doctor/schedules")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private String token(long id, UserRole role) {
        UserAccount user = new UserAccount(id, "schedule-" + id, "unused", role, UserStatus.ENABLED, 0);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return "Bearer " + tokens.issue(user);
    }
}
