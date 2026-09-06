package com.qqlin.medflow.shared.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException{
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode,"errorCode must not be null").getDefaultMessage());
        this.errorCode = errorCode;
    }
    public BusinessException(ErrorCode errorCode,String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode,"errorCode must not be null");
    }

    public ErrorCode getErrorCode(){
        return errorCode;
    }
}
