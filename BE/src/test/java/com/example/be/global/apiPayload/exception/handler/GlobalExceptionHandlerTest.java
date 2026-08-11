package com.example.be.global.apiPayload.exception.handler;

import com.example.be.global.apiPayload.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void badRequestResponseDoesNotExposeExceptionMessage() {
        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleBadRequest(
                new IllegalArgumentException("Failed to parse request body into InternalDto")
        );

        assertEquals(400, response.getStatusCode().value());
        assertEquals("COMMON400", response.getBody().getCode());
        assertEquals("입력값 검증 실패입니다.", response.getBody().getMessage());
        assertEquals(Map.of(), response.getBody().getResult());
    }
}
