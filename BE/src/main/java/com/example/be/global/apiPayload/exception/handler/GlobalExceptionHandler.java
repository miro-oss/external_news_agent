package com.example.be.global.apiPayload.exception.handler;

import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.BaseErrorCode;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<String, Object> EMPTY_RESULT = Map.of();

    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGeneralException(GeneralException exception) {
        BaseErrorCode code = exception.getCode();
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.onFailure(code, exception.getMessage(), EMPTY_RESULT));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBadRequest(Exception exception) {
        return failure(GeneralErrorCode.BAD_REQUEST, GeneralErrorCode.BAD_REQUEST.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleException(Exception exception) {
        return failure(GeneralErrorCode.INTERNAL_SERVER_ERROR, GeneralErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> failure(BaseErrorCode code, String message) {
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.onFailure(code, message, EMPTY_RESULT));
    }
}
