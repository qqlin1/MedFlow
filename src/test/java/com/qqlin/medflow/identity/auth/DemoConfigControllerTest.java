package com.qqlin.medflow.identity.auth;

import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DemoConfigController.class, properties = {
        "medflow.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "medflow.security.jwt.issuer=medflow-demo-config-test"
})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class DemoConfigControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean DbUserDetailsService userDetails;

    @Test
    void anonymousUiFlagIsDisabledByDefaultAndContainsNoCredentials() throws Exception {
        mvc.perform(get("/api/v1/auth/demo-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.workflowEnabled").value(false))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void enabledUiFlagOnlyReturnsFeatureBooleans() throws Exception {
        MockMvc enabled = MockMvcBuilders.standaloneSetup(new DemoConfigController(true,true)).build();
        enabled.perform(get("/api/v1/auth/demo-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.workflowEnabled").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void workflowCannotBeEnabledWithoutDemoAccounts() throws Exception {
        MockMvc disabled = MockMvcBuilders.standaloneSetup(new DemoConfigController(false,true)).build();
        disabled.perform(get("/api/v1/auth/demo-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowEnabled").value(false));
    }

    @Test
    void anotherAuthPathDoesNotBecomePublic() throws Exception {
        mvc.perform(get("/api/v1/auth/private-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicFlagMatcherDoesNotPermitOtherMethods() throws Exception {
        mvc.perform(post("/api/v1/auth/demo-config"))
                .andExpect(status().isUnauthorized());
    }
}
