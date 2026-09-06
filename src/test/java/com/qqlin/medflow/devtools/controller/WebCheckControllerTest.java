package com.qqlin.medflow.devtools.controller;

import com.qqlin.medflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebCheckController.class)
@ActiveProfiles("local")
@Import(GlobalExceptionHandler.class)
class WebCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successShouldReturn200() throws Exception {
        mockMvc.perform(get("/dev/web/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value("Web链路正常"));
    }

    @Test
    void soldOutShouldReturn409() throws Exception {
        mockMvc.perform(get("/dev/web/sold-out"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_SOLD_OUT"))
                .andExpect(jsonPath("$.message").value("该号源已约满"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void noResourceFoundShouldReturn404() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void unexpectedExceptionShouldReturn500() throws Exception {
        mockMvc.perform(get("/dev/web/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void validRequestShouldReturn200() throws Exception {
        mockMvc.perform(post("/dev/web/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patientId": 1,
                                  "slotId": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.patientId").value(1))
                .andExpect(jsonPath("$.data.slotId").value(10));
    }

    @Test
    void missingPatientIdShouldReturn400() throws Exception {
        mockMvc.perform(post("/dev/web/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slotId": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("就诊人ID不能为空"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void zeroSlotIdShouldReturn400() throws Exception {
        mockMvc.perform(post("/dev/web/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patientId": 1,
                                  "slotId": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("号源ID必须大于0"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void nonNumericPatientIdShouldReturn400() throws Exception {
        mockMvc.perform(post("/dev/web/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patientId": "abc",
                                  "slotId": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}