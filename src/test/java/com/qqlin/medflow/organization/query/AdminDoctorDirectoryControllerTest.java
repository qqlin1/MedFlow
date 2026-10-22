package com.qqlin.medflow.organization.query;

import com.qqlin.medflow.identity.domain.*;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import com.qqlin.medflow.organization.controller.AdminOrganizationController;
import com.qqlin.medflow.organization.controller.PatientDirectoryController;
import com.qqlin.medflow.organization.domain.*;
import com.qqlin.medflow.organization.dto.DoctorResponse;
import com.qqlin.medflow.organization.service.OrganizationService;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AdminOrganizationController.class, PatientDirectoryController.class}, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=admin-doctor-directory-test", "medflow.security.jwt.access-token-ttl=PT30M"})
@Import({SecurityConfig.class, JwtService.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class, GlobalExceptionHandler.class})
class AdminDoctorDirectoryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @MockitoBean OrganizationService organization;
    @MockitoBean AdminDoctorDirectoryQuery directory;
    @MockitoBean UserRepository users;
    @MockitoBean DbUserDetailsService userDetails;

    @Test void adminDirectoryIncludesUsernameAndPreservesExistingFields() throws Exception {
        when(directory.findEnabledByDepartment(3)).thenReturn(List.of(
                new DoctorResponse(7, 11, 3, "王医生", ResourceStatus.ENABLED, "doctor")));
        mvc.perform(get("/api/v1/admin/doctors").param("departmentId", "3")
                        .header(HttpHeaders.AUTHORIZATION, token(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].userId").value(11))
                .andExpect(jsonPath("$.data[0].departmentId").value(3))
                .andExpect(jsonPath("$.data[0].name").value("王医生"))
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].username").value("doctor"));
        verifyNoInteractions(organization);
    }

    @Test void patientDirectoryDoesNotExposeLoginUsernameOrAccountId() throws Exception {
        when(organization.findEnabledDoctorsByDepartmentId(3)).thenReturn(List.of(
                new Doctor(7, 11, 3, "王医生", ResourceStatus.ENABLED)));
        mvc.perform(get("/api/v1/patient/doctors").param("departmentId", "3")
                        .header(HttpHeaders.AUTHORIZATION, token(UserRole.PATIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("王医生"))
                .andExpect(jsonPath("$.data[0].username").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
        verifyNoInteractions(directory);
    }

    @Test void patientAndDoctorCannotReadAdministrativeLoginDirectory() throws Exception {
        for (UserRole role : List.of(UserRole.PATIENT, UserRole.DOCTOR)) {
            mvc.perform(get("/api/v1/admin/doctors").param("departmentId", "3")
                            .header(HttpHeaders.AUTHORIZATION, token(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(directory, organization);
    }

    @Test void disabledDepartmentStillReturnsBusinessConflict() throws Exception {
        when(directory.findEnabledByDepartment(3)).thenThrow(new BusinessException(ErrorCode.DEPARTMENT_DISABLED));
        mvc.perform(get("/api/v1/admin/doctors").param("departmentId", "3")
                        .header(HttpHeaders.AUTHORIZATION, token(UserRole.ADMIN)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPARTMENT_DISABLED"));
    }

    private String token(UserRole role) {
        long id = 301 + role.ordinal();
        UserAccount account = new UserAccount(id, "directory-" + role.name(), "unused", role, UserStatus.ENABLED, 0);
        when(users.findById(id)).thenReturn(Optional.of(account));
        return "Bearer " + tokens.issue(account);
    }
}
