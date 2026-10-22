package com.qqlin.medflow.scheduling.controller;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.DbUserDetailsService;
import com.qqlin.medflow.identity.security.JwtService;
import com.qqlin.medflow.identity.security.RestAccessDeniedHandler;
import com.qqlin.medflow.identity.security.RestAuthenticationEntryPoint;
import com.qqlin.medflow.identity.security.SecurityConfig;
import com.qqlin.medflow.identity.security.SecurityErrorResponseWriter;
import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import com.qqlin.medflow.scheduling.domain.AvailableSlotPage;
import com.qqlin.medflow.scheduling.service.SlotQueryService;
import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PatientSlotController.class, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=medflow-slot-query-test",
        "medflow.security.jwt.access-token-ttl=PT30M"
})
@Import({SecurityConfig.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class PatientSlotControllerTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SlotQueryService slotQueryService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private DbUserDetailsService dbUserDetailsService;

    @Test
    void shouldReturnBookableSlotDisplayFieldsWithDefaultPagination() throws Exception {
        AvailableSlot slot = new AvailableSlot(
                101L, 102L, 11L, "内科", 22L, "陈医生", 33L, "第一诊室",
                WORK_DATE, LocalTime.of(9, 0), LocalTime.of(9, 30),
                WORK_DATE.atTime(8, 30), 5);
        when(slotQueryService.findAvailable(WORK_DATE, null, null, 1, 20))
                .thenReturn(new AvailableSlotPage(List.of(slot), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/patient/slots")
                        .param("workDate", "2026-10-05")
                        .header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].slotId").value(101))
                .andExpect(jsonPath("$.data.items[0].scheduleId").value(102))
                .andExpect(jsonPath("$.data.items[0].departmentName").value("内科"))
                .andExpect(jsonPath("$.data.items[0].doctorName").value("陈医生"))
                .andExpect(jsonPath("$.data.items[0].clinicRoomName").value("第一诊室"))
                .andExpect(jsonPath("$.data.items[0].workDate").value("2026-10-05"))
                .andExpect(jsonPath("$.data.items[0].remainingCapacity").value(5))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());

        verify(slotQueryService).findAvailable(WORK_DATE, null, null, 1, 20);
    }

    @Test
    void shouldPassOptionalFiltersAndPageToService() throws Exception {
        when(slotQueryService.findAvailable(WORK_DATE, 11L, 22L, 2, 50))
                .thenReturn(new AvailableSlotPage(List.of(), 1L, 2, 50));

        mockMvc.perform(get("/api/v1/patient/slots")
                        .param("workDate", "2026-10-05")
                        .param("departmentId", "11")
                        .param("doctorId", "22")
                        .param("page", "2")
                        .param("size", "50")
                        .header("Authorization", token(UserRole.PATIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(slotQueryService).findAvailable(WORK_DATE, 11L, 22L, 2, 50);
    }

    @Test
    void shouldRejectMissingOrMalformedDateBeforeService() throws Exception {
        String bearer = token(UserRole.PATIENT);
        mockMvc.perform(get("/api/v1/patient/slots").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/patient/slots")
                        .param("workDate", "not-a-date").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(slotQueryService);
    }

    @Test
    void shouldRejectInvalidIdsOrPaginationBeforeService() throws Exception {
        String bearer = token(UserRole.PATIENT);
        for (String[] parameter : new String[][] {
                {"departmentId", "0"}, {"doctorId", "-1"}, {"doctorId", "abc"},
                {"page", "0"}, {"size", "0"}, {"size", "51"}
        }) {
            mockMvc.perform(get("/api/v1/patient/slots")
                            .param("workDate", "2026-10-05")
                            .param(parameter[0], parameter[1])
                            .header("Authorization", bearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(slotQueryService);
    }

    @Test
    void shouldRejectMissingTokenAndWrongRolesBeforeService() throws Exception {
        mockMvc.perform(get("/api/v1/patient/slots").param("workDate", "2026-10-05"))
                .andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.DOCTOR)) {
            mockMvc.perform(get("/api/v1/patient/slots")
                            .param("workDate", "2026-10-05")
                            .header("Authorization", token(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(slotQueryService);
    }

    private String token(UserRole role) {
        UserAccount account = new UserAccount(101L, "slot-query-user", "unused",
                role, UserStatus.ENABLED, 0);
        when(userRepository.findById(101L)).thenReturn(Optional.of(account));
        return "Bearer " + jwtService.issue(account);
    }
}
