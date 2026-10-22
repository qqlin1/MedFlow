package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={PatientHistoryController.class,DoctorPatientHistoryController.class},properties={
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=patient-history-controller-test","medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class,JwtCurrentUserResolver.class,JwtService.class,RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,SecurityErrorResponseWriter.class,GlobalExceptionHandler.class})
class PatientHistoryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean PatientHistoryService history;
    @MockitoBean UserRepository accounts;
    @MockitoBean DbUserDetailsService details;

    @Test void anonymousCannotReadPatientHistory() throws Exception {
        mvc.perform(get("/api/v1/patients/20/history")).andExpect(status().isUnauthorized());verifyNoInteractions(history);
    }
    @Test void adminCannotReadPatientHistoryUsingPatientRoute() throws Exception {
        mvc.perform(get("/api/v1/patients/20/history/40").header("Authorization",token(UserRole.ADMIN)))
                .andExpect(status().isForbidden());verifyNoInteractions(history);
    }
    @Test void adminCannotReadHistoryUsingDoctorRoute() throws Exception {
        mvc.perform(get("/api/v1/doctor/appointments/30/patient-history").header("Authorization",token(UserRole.ADMIN)))
                .andExpect(status().isForbidden());verifyNoInteractions(history);
    }
    @Test void patientCannotUseDoctorAnchorRoute() throws Exception {
        mvc.perform(get("/api/v1/doctor/appointments/30/patient-history/40").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isForbidden());verifyNoInteractions(history);
    }
    @Test void doctorCannotReadPatientRouteByArbitraryPatientId() throws Exception {
        mvc.perform(get("/api/v1/patients/20/history").header("Authorization",token(UserRole.DOCTOR)))
                .andExpect(status().isForbidden());verifyNoInteractions(history);
    }
    @Test void patientPageUsesJwtUidAndExplicitPatientScope() throws Exception {
        mvc.perform(get("/api/v1/patients/20/history?page=2&size=3").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk());verify(history).patientPage(10,20,2,3);
    }
    @Test void doctorDetailUsesJwtUidAndAnchorInsteadOfArbitraryPatientParameter() throws Exception {
        mvc.perform(get("/api/v1/doctor/appointments/30/patient-history/40?patientId=999&doctorId=888")
                .header("Authorization",token(UserRole.DOCTOR))).andExpect(status().isOk());
        verify(history).doctorDetail(10,30,40);
    }
    @Test void historyPageSizeIsCappedAtFifty() throws Exception {
        mvc.perform(get("/api/v1/patients/20/history?size=51").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isBadRequest());verifyNoInteractions(history);
    }
    @Test void negativeAnchorIsRejected() throws Exception {
        mvc.perform(get("/api/v1/doctor/appointments/0/patient-history").header("Authorization",token(UserRole.DOCTOR)))
                .andExpect(status().isBadRequest());verifyNoInteractions(history);
    }
    private String token(UserRole role) {
        UserAccount account=new UserAccount(10,"patient-history-test","unused",role,UserStatus.ENABLED,0);
        when(accounts.findById(10)).thenReturn(Optional.of(account));return "Bearer "+jwt.issue(account);
    }
}
