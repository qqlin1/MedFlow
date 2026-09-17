package com.qqlin.medflow.shared.exception;

import com.qqlin.medflow.shared.web.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.core.AuthenticationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    GlobalExceptionHandler.class
            );

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(
            BusinessException exception
    ) {
        return buildResponse(
                exception.getErrorCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleRequestBodyValidation(
            MethodArgumentNotValidException exception
    ) {
        String message =
                ErrorCode.VALIDATION_FAILED.getDefaultMessage();

        FieldError fieldError =
                exception.getBindingResult().getFieldError();

        if (fieldError != null
                && fieldError.getDefaultMessage() != null) {
            message = fieldError.getDefaultMessage();
        }

        return buildResponse(
                ErrorCode.VALIDATION_FAILED,
                message
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValidation(
            HandlerMethodValidationException exception
    ) {
        String message =
                ErrorCode.VALIDATION_FAILED.getDefaultMessage();

        if (!exception.getAllErrors().isEmpty()) {
            String validationMessage =
                    exception.getAllErrors()
                            .get(0)
                            .getDefaultMessage();

            if (validationMessage != null) {
                message = validationMessage;
            }
        }

        return buildResponse(
                ErrorCode.VALIDATION_FAILED,
                message
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMalformedRequestBody(
            HttpMessageNotReadableException exception
    ) {
        return buildResponse(
                ErrorCode.MALFORMED_REQUEST_BODY
        );
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<Result<Void>> handleInvalidRequestParameter(
            Exception exception
    ) {
        return buildResponse(ErrorCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(
            NoResourceFoundException exception
    ) {
        return buildResponse(
                ErrorCode.NOT_FOUND
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(
            Exception exception
    ) {
        LOGGER.error(
                "Unexpected server error",
                exception
        );

        return buildResponse(
                ErrorCode.INTERNAL_SERVER_ERROR
        );
    }

    private ResponseEntity<Result<Void>> buildResponse(
            ErrorCode errorCode
    ) {
        return buildResponse(
                errorCode,
                errorCode.getDefaultMessage()
        );
    }

    private ResponseEntity<Result<Void>> buildResponse(
            ErrorCode errorCode,
            String message
    ) {
        String responseMessage = message;

        if (responseMessage == null
                || responseMessage.isBlank()) {
            responseMessage =
                    errorCode.getDefaultMessage();
        }

        Result<Void> body = Result.fail(
                errorCode.getCode(),
                responseMessage
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(body);
    }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(
            AuthenticationException exception
    ) {
        return buildResponse(
                ErrorCode.INVALID_CREDENTIALS
        );
    }
}
