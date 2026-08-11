package com.example.be.global.apiPayload;

import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successResponseUsesCommonEnvelope() throws Exception {
        ApiResponse<Map<String, Long>> response = ApiResponse.of(
                GeneralSuccessCode.OK,
                Map.of("id", 1L)
        );

        Map<?, ?> json = objectMapper.readValue(objectMapper.writeValueAsString(response), Map.class);

        assertEquals(true, json.get("isSuccess"));
        assertEquals("COMMON200", json.get("code"));
        assertEquals("성공입니다.", json.get("message"));
        assertEquals(Map.of("id", 1), json.get("result"));
        assertEquals(Set.of("isSuccess", "code", "message", "result"), json.keySet());
    }

    @Test
    void failureResponseUsesCommonEnvelope() throws Exception {
        ApiResponse<Map<String, Object>> response = ApiResponse.onFailure(
                GeneralErrorCode.BAD_REQUEST,
                Map.of()
        );

        Map<?, ?> json = objectMapper.readValue(objectMapper.writeValueAsString(response), Map.class);

        assertEquals(false, json.get("isSuccess"));
        assertEquals("COMMON400", json.get("code"));
        assertEquals("입력값 검증 실패입니다.", json.get("message"));
        assertEquals(Map.of(), json.get("result"));
    }
}
