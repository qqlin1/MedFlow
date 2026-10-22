package com.qqlin.medflow.encounter;

import com.qqlin.medflow.encounter.controller.DoctorEncounterController;
import com.qqlin.medflow.encounter.controller.PatientEncounterController;
import com.qqlin.medflow.encounter.service.EncounterService;
import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {DoctorEncounterController.class, PatientEncounterController.class}, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=encounter-controller-test", "medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class, JwtCurrentUserResolver.class, JwtService.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class EncounterControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean EncounterService service;
    @MockitoBean UserRepository accounts;
    @MockitoBean DbUserDetailsService details;

    @Test void anonymousCannotCheckIn() throws Exception {
        mvc.perform(post("/api/v1/appointments/30/check-in")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void patientCannotAccessDoctorQueue() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters").header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void adminCannotReadMedicalRecordThroughDoctorRoute() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters/40").header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void doctorCannotInvokePatientCheckIn() throws Exception {
        mvc.perform(post("/api/v1/appointments/30/check-in").header("Authorization", token(UserRole.DOCTOR)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void checkInUsesJwtIdentity() throws Exception {
        mvc.perform(post("/api/v1/appointments/30/check-in").header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isOk());
        verify(service).checkIn(10, 30);
    }

    @Test void missingRecordVersionIsRejectedBeforeService() throws Exception {
        mvc.perform(put("/api/v1/doctor/encounters/40/medical-record")
                .header("Authorization", token(UserRole.DOCTOR)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chiefComplaint\":\"发热\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test void negativeCompletionVersionIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/doctor/encounters/40/complete")
                .header("Authorization", token(UserRole.DOCTOR)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void queuePageSizeIsCappedBeforeService() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters?size=51").header("Authorization", token(UserRole.DOCTOR)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void unknownQueueStateIsRejected() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters?status=BOOKED").header("Authorization", token(UserRole.DOCTOR)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String token(UserRole role) {
        UserAccount account = new UserAccount(10, "encounter-test", "unused", role, UserStatus.ENABLED, 0);
        when(accounts.findById(10)).thenReturn(Optional.of(account));
        return "Bearer " + jwt.issue(account);
    }
}
