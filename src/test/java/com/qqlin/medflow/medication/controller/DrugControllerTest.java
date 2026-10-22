package com.qqlin.medflow.medication.controller;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.medication.domain.*;
import com.qqlin.medflow.medication.dto.*;
import com.qqlin.medflow.medication.service.DrugService;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AdminDrugController.class, DoctorDrugController.class}, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=drug-controller-test", "medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class, JwtService.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class DrugControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @MockitoBean DrugService service;
    @MockitoBean UserRepository users;
    @MockitoBean DbUserDetailsService userDetails;

    @Test void administratorCanCreateDrugWithExactContract() throws Exception {
        when(service.create(any())).thenReturn(drug());
        mvc.perform(post("/api/v1/admin/drugs").header(HttpHeaders.AUTHORIZATION, token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body("12.30")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("D001"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.unitPrice").value(12.3));
        verify(service).create(new CreateDrugRequest("D001", "模拟药品", "10mg", "盒", new BigDecimal("12.30")));
    }

    @Test void doctorCannotBroadenEnabledCatalogByPassingStatusParameter() throws Exception {
        when(service.doctorPage(null, 1, 20)).thenReturn(new DrugPageResponse(List.of(drug()), 1, 1, 20));
        mvc.perform(get("/api/v1/doctor/drugs").param("status", "DISABLED")
                        .header(HttpHeaders.AUTHORIZATION, token(UserRole.DOCTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("ENABLED"));
        verify(service).doctorPage(null, 1, 20);
    }

    @Test void patientCannotReadCatalogAndDoctorCannotMaintainIt() throws Exception {
        mvc.perform(get("/api/v1/doctor/drugs").header(HttpHeaders.AUTHORIZATION, token(UserRole.PATIENT)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/drugs").header(HttpHeaders.AUTHORIZATION, token(UserRole.DOCTOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body("12.30")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void pricesCannotBeZeroRoundedOrOutsideDatabaseRange() throws Exception {
        String authorization = token(UserRole.ADMIN);
        for (String price : List.of("0", "-0.01", "0.001", "1000000")) {
            mvc.perform(post("/api/v1/admin/drugs").header(HttpHeaders.AUTHORIZATION, authorization)
                            .contentType(MediaType.APPLICATION_JSON).content(body(price)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    @Test void editRequiresVersionAndValidStatus() throws Exception {
        String authorization = token(UserRole.ADMIN);
        for (String content : List.of(
                "{\"name\":\"药品\",\"specification\":\"10mg\",\"unit\":\"盒\",\"unitPrice\":1,\"status\":\"ENABLED\"}",
                "{\"name\":\"药品\",\"specification\":\"10mg\",\"unit\":\"盒\",\"unitPrice\":1,\"status\":\"UNKNOWN\",\"version\":0}")) {
            mvc.perform(put("/api/v1/admin/drugs/7").header(HttpHeaders.AUTHORIZATION, authorization)
                            .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void staleEditorReceivesDomain409() throws Exception {
        when(service.update(eq(7L), any())).thenThrow(new BusinessException(ErrorCode.DRUG_VERSION_CONFLICT));
        mvc.perform(put("/api/v1/admin/drugs/7").header(HttpHeaders.AUTHORIZATION, token(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"药品\",\"specification\":\"10mg\",\"unit\":\"盒\",\"unitPrice\":1,\"status\":\"DISABLED\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRUG_VERSION_CONFLICT"));
    }

    @Test void invalidPagingAndUnknownStatusFailBeforeService() throws Exception {
        String authorization = token(UserRole.ADMIN);
        mvc.perform(get("/api/v1/admin/drugs").param("size", "51").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/drugs").param("status", "UNKNOWN").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedCatalogReadReturns401() throws Exception {
        mvc.perform(get("/api/v1/doctor/drugs")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private String token(UserRole role) {
        long id = role.ordinal() + 100L;
        UserAccount user = new UserAccount(id, "drug-" + role.name(), "unused", role, UserStatus.ENABLED, 0);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return "Bearer " + tokens.issue(user);
    }
    private String body(String price) {
        return "{\"code\":\"D001\",\"name\":\"模拟药品\",\"specification\":\"10mg\",\"unit\":\"盒\",\"unitPrice\":" + price + "}";
    }
    private Drug drug() { return new Drug(7, "D001", "模拟药品", "10mg", "盒", new BigDecimal("12.30"), DrugStatus.ENABLED, 0); }
}
