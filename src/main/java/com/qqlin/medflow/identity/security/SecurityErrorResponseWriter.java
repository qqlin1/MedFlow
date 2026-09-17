package com.qqlin.medflow.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.shared.exception.ErrorCode;
import com.qqlin.medflow.shared.web.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {

        Result<Void> body = Result.fail(
                errorCode.getCode(),
                errorCode.getDefaultMessage()
        );

        response.setStatus(
                errorCode.getHttpStatus().value()
        );
        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );
        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        objectMapper.writeValue(
                response.getOutputStream(),
                body
        );
    }
}
