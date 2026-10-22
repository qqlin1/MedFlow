package com.qqlin.medflow.appointment.query;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=AppointmentVisitInfoController.class,properties={
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=visit-summary-controller-test","medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class,JwtCurrentUserResolver.class,JwtService.class,RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,SecurityErrorResponseWriter.class,GlobalExceptionHandler.class})
class AppointmentVisitInfoControllerTest {
    private static final String PATH="/api/v1/appointments/visit-summaries";
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean AppointmentVisitInfoMapper mapper;
    @MockitoBean UserRepository accounts;
    @MockitoBean DbUserDetailsService details;

    @Test void batchDeduplicatesAndMakesOneQueryUsingJwtOwner() throws Exception {
        var deadline=LocalDateTime.of(2026,10,20,7,45);
        var info=new AppointmentVisitInfo(3,20,"示例患者","示例医生","示例科室","一诊室",
                LocalDate.of(2026,10,20),LocalTime.of(8,0),LocalTime.of(8,30),"BOOKED",
                deadline,deadline,null,null,true);
        when(mapper.findOwnedBatch(List.of(3L,4L),10)).thenReturn(List.of(info));
        mvc.perform(get(PATH).param("ids","3,4,3").param("userId","999")
                        .header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].appointmentId").value(3))
                .andExpect(jsonPath("$.data[0].workDate").value("2026-10-20"))
                .andExpect(jsonPath("$.data[0].doctorName").value("示例医生"))
                .andExpect(jsonPath("$.data[0].departmentName").value("示例科室"))
                .andExpect(jsonPath("$.data[0].canCheckIn").value(true));
        verify(mapper).findOwnedBatch(List.of(3L,4L),10);
        verifyNoMoreInteractions(mapper);
    }

    @Test void foreignAndUnknownIdsMayYieldAnEmptyArray() throws Exception {
        when(mapper.findOwnedBatch(List.of(99L),10)).thenReturn(List.of());
        mvc.perform(get(PATH).param("ids","99").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void missingIdsAreRejectedBeforeQuery() throws Exception {
        mvc.perform(get(PATH).header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(mapper);
    }

    @Test void invalidAndEmptyIdsAreRejectedBeforeQuery() throws Exception {
        String bearer=token(UserRole.PATIENT);
        for(String ids:List.of(""," ","0","-1","1.5","1e2","1,,2","1,","a","1 OR 1=1",
                "9223372036854775808")) {
            mvc.perform(get(PATH).param("ids",ids).header("Authorization",bearer))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(mapper);
    }

    @Test void moreThanFiftyInputIdsAreRejectedEvenWhenRepeated() throws Exception {
        String ids=IntStream.range(0,51).mapToObj(i->"1").collect(Collectors.joining(","));
        mvc.perform(get(PATH).param("ids",ids).header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(mapper);
    }

    @Test void fiftyIdsAreAcceptedInOneQuery() throws Exception {
        var ids=IntStream.rangeClosed(1,50).mapToObj(i->(long)i).toList();
        when(mapper.findOwnedBatch(ids,10)).thenReturn(List.of());
        mvc.perform(get(PATH).param("ids",ids.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .header("Authorization",token(UserRole.PATIENT))).andExpect(status().isOk());
        verify(mapper).findOwnedBatch(ids,10);verifyNoMoreInteractions(mapper);
    }

    @Test void anonymousRequestIsRejectedBeforeQuery() throws Exception {
        mvc.perform(get(PATH).param("ids","3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }

    @Test void doctorAndAdminCannotUsePatientSummaryRoute() throws Exception {
        for(UserRole role:List.of(UserRole.DOCTOR,UserRole.ADMIN)) {
            mvc.perform(get(PATH).param("ids","3").header("Authorization",token(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(mapper);
    }

    private String token(UserRole role) {
        UserAccount account=new UserAccount(10,"visit-summary-test","unused",role,UserStatus.ENABLED,0);
        when(accounts.findById(10)).thenReturn(Optional.of(account));return "Bearer "+jwt.issue(account);
    }
}
