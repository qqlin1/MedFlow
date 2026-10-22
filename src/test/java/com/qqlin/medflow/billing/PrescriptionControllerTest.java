package com.qqlin.medflow.billing;

import com.qqlin.medflow.billing.controller.*;
import com.qqlin.medflow.billing.dto.SavePrescriptionRequest;
import com.qqlin.medflow.billing.service.PrescriptionService;
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
import java.util.List;
import java.util.Optional;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {DoctorPrescriptionController.class,PatientBillingController.class},properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=billing-controller-test","medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class,JwtCurrentUserResolver.class,JwtService.class,RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,SecurityErrorResponseWriter.class,GlobalExceptionHandler.class})
class PrescriptionControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean PrescriptionService service;
    @MockitoBean UserRepository accounts;
    @MockitoBean DbUserDetailsService details;

    @Test void noTokenCannotPay() throws Exception {
        mvc.perform(post("/api/v1/patient/bills/60/pay")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void adminCannotPayOnPatientBehalf() throws Exception {
        mvc.perform(post("/api/v1/patient/bills/60/pay").header("Authorization",token(UserRole.ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void patientCannotReadDoctorDraft() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters/30/prescription").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void doctorCannotListPatientPrescriptions() throws Exception {
        mvc.perform(get("/api/v1/patient/prescriptions").header("Authorization",token(UserRole.DOCTOR)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void absentPrescriptionProducesSuccessfulNullData() throws Exception {
        mvc.perform(get("/api/v1/doctor/encounters/30/prescription").header("Authorization",token(UserRole.DOCTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
        verify(service).doctorDetail(10,30);
    }
    @Test void quantityAboveLimitIsRejected() throws Exception {
        save("{\"version\":0,\"items\":[{\"drugId\":50,\"quantity\":1001}]}",400);
        verifyNoInteractions(service);
    }
    @Test void fractionalQuantityIsNotTruncatedToInteger() throws Exception {
        save("{\"version\":0,\"items\":[{\"drugId\":50,\"quantity\":1.5}]}",400);
        verifyNoInteractions(service);
    }
    @Test void fractionalVersionIsNotTruncatedToPriorVersion() throws Exception {
        save("{\"version\":0.9,\"items\":[{\"drugId\":50,\"quantity\":1}]}",400);
        verifyNoInteractions(service);
    }
    @Test void nullItemIsRejected() throws Exception {
        save("{\"version\":0,\"items\":[null]}",400);
        verifyNoInteractions(service);
    }
    @Test void validDraftUsesIdentityFromJwtAndIgnoresClientPriceFields() throws Exception {
        save("{\"version\":0,\"totalAmount\":0.01,\"items\":[{\"drugId\":50,\"quantity\":2,\"unitPrice\":0.01}]}",200);
        verify(service).save(10,30,new SavePrescriptionRequest(0L,List.of(new SavePrescriptionRequest.Item(50L,2,null))));
    }
    @Test void discardRequiresVersion() throws Exception {
        mvc.perform(post("/api/v1/doctor/encounters/30/prescription/discard").header("Authorization",token(UserRole.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void patientListCannotRequestMoreThanFifty() throws Exception {
        mvc.perform(get("/api/v1/patient/prescriptions?size=51").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void payUsesPatientUidAndHasNoClientAmount() throws Exception {
        mvc.perform(post("/api/v1/patient/bills/60/pay").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk());
        verify(service).pay(10,60);
    }
    private void save(String json,int expected) throws Exception {
        mvc.perform(put("/api/v1/doctor/encounters/30/prescription").header("Authorization",token(UserRole.DOCTOR))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().is(expected));
    }
    private String token(UserRole role) {
        UserAccount account=new UserAccount(10,"billing-test","unused",role,UserStatus.ENABLED,0);
        when(accounts.findById(10)).thenReturn(Optional.of(account));
        return "Bearer "+jwt.issue(account);
    }
}
