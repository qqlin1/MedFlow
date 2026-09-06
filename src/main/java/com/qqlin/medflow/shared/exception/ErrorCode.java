package com.qqlin.medflow.shared.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "请求参数校验失败"
    ),

    MALFORMED_REQUEST_BODY(
            HttpStatus.BAD_REQUEST,
            "MALFORMED_REQUEST_BODY",
            "请求体格式不正确"
    ),

    NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            "请求的资源不存在"
    ),

    SLOT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SLOT_NOT_FOUND",
            "号源不存在"
    ),

    SLOT_SOLD_OUT(
            HttpStatus.CONFLICT,
            "SLOT_SOLD_OUT",
            "该号源已约满"
    ),

    DUPLICATE_APPOINTMENT(
            HttpStatus.CONFLICT,
            "DUPLICATE_APPOINTMENT",
            "该就诊人已经预约过此号源"
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "服务器内部错误"
    );
    private final HttpStatus httpStatus;
    private  final String code;
    private  final String defaultMessage;
    ErrorCode(
            HttpStatus httpStatus,
            String code,
            String defaultMessage
    ) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

}
