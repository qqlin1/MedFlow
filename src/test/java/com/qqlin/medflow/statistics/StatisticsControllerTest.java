package com.qqlin.medflow.statistics;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StatisticsController.class, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=statistics-test", "medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class, JwtService.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class StatisticsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @MockitoBean StatisticsService service;
    @MockitoBean UserRepository users;
    @MockitoBean DbUserDetailsService details;

    @Test void adminReceivesRangeAndStableEnvelope() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 20);
        when(service.get(date, date)).thenReturn(new StatisticsResponse(date, date, List.of(), List.of(), List.of()));
        mvc.perform(get("/api/v1/admin/statistics").param("from", date.toString()).param("to", date.toString())
                        .header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.from").value("2026-10-20"))
                .andExpect(jsonPath("$.data.doctorWorkload").isArray());
    }

    @Test void doctorAndPatientCannotReadAdministrativeStatistics() throws Exception {
        for (UserRole role : List.of(UserRole.DOCTOR, UserRole.PATIENT)) {
            mvc.perform(get("/api/v1/admin/statistics").header("Authorization", token(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/statistics")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private String token(UserRole role) {
        var account = new UserAccount(66L, "statistics-" + role.name(), "unused", role, UserStatus.ENABLED, 0);
        when(users.findById(66L)).thenReturn(Optional.of(account));
        return "Bearer " + tokens.issue(account);
    }
}
