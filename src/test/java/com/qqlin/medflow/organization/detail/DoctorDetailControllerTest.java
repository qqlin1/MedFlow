package com.qqlin.medflow.organization.detail;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DoctorDetailController.class,properties={
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=doctor-cache-test","medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class,JwtService.class,RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,SecurityErrorResponseWriter.class})
class DoctorDetailControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @MockitoBean DoctorDetailService service;
    @MockitoBean UserRepository users;
    @MockitoBean DbUserDetailsService details;
    @Test void patientReceivesOnlyPublicDoctorFields() throws Exception {
        when(service.get(7)).thenReturn(new DoctorDetail(7,2,"王医生","全科"));
        mvc.perform(get("/api/v1/patient/doctors/7").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.departmentName").value("全科"))
                .andExpect(jsonPath("$.data.userId").doesNotExist()).andExpect(jsonPath("$.data.username").doesNotExist());
    }
    @Test void onlyAdminCanEditProfile() throws Exception {
        for(var role:List.of(UserRole.PATIENT,UserRole.DOCTOR))
            mvc.perform(put("/api/v1/admin/doctors/7/profile").header("Authorization",token(role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"王医生\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void adminCanUpdateName() throws Exception {
        when(service.updateProfile(7,"新姓名",null,null,null)).thenReturn(new DoctorDetail(7,2,"新姓名","全科"));
        mvc.perform(put("/api/v1/admin/doctors/7/profile").header("Authorization",token(UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新姓名\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("新姓名"));
    }
    @Test void adminCanReadAndMaintainThePublicBiographyWithoutExposingLoginFields() throws Exception {
        var biography=new DoctorDetail(7,2,"王医生","全科","主治医师","复诊咨询","虚构医生简介");
        when(service.get(7)).thenReturn(biography);
        when(service.updateProfile(7,"王医生","主治医师","复诊咨询","虚构医生简介")).thenReturn(biography);
        mvc.perform(get("/api/v1/admin/doctors/7/profile").header("Authorization",token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.specialty").value("复诊咨询"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(put("/api/v1/admin/doctors/7/profile").header("Authorization",token(UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"王医生\",\"title\":\"主治医师\",\"specialty\":\"复诊咨询\",\"introduction\":\"虚构医生简介\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.introduction").value("虚构医生简介"));
    }
    @Test void directorySupportsAllDoctorsAndRejectsOversizedSearch() throws Exception {
        when(service.directory(null,"复诊")).thenReturn(List.of(new DoctorDetail(7,2,"王医生","全科","主治医师","复诊咨询","")));
        mvc.perform(get("/api/v1/patient/doctor-directory").param("query","复诊").header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].title").value("主治医师"));
        mvc.perform(get("/api/v1/patient/doctor-directory").param("query","长".repeat(65)).header("Authorization",token(UserRole.PATIENT)))
                .andExpect(status().isBadRequest());
    }
    @Test void badInputDoesNotReachService() throws Exception {
        mvc.perform(get("/api/v1/patient/doctors/0").header("Authorization",token(UserRole.PATIENT))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/doctors/7/profile").header("Authorization",token(UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/patient/doctors/7")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/admin/doctors/7/profile")).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    String token(UserRole role) {
        var account=new UserAccount(80L,"cache-"+role.name(),"unused",role,UserStatus.ENABLED,0);
        when(users.findById(80L)).thenReturn(Optional.of(account)); return "Bearer "+tokens.issue(account);
    }
}
