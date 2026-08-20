package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentErrorResponse;
import com.example.be.global.config.RestClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AgentClient {

    static final String AGENT_TOKEN_HEADER = "X-Agent-Token";
    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final RestClient restClient;

    @Autowired
    public AgentClient(RestClientFactory restClientFactory, AgentProperties properties) {
        this(restClientFactory.create(
                properties.getConnectTimeout(), properties.getAnalyzeTimeout()), properties);
    }

    AgentClient(RestClient.Builder builder, AgentProperties properties) {
        RestClient.Builder configured = builder.baseUrl(properties.getBaseUrl());
        if (StringUtils.hasText(properties.getToken())) {
            configured.defaultHeader(AGENT_TOKEN_HEADER, properties.getToken());
        }
        this.restClient = configured.build();
    }

    public AgentAnalyzeResponse analyze(AgentAnalyzeRequest request) {
        try {
            AgentAnalyzeResponse response = restClient.post()
                    .uri("/v1/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AgentAnalyzeResponse.class);
            if (response == null) {
                throw new AgentClientException("SCHEMA_VIOLATION", "Agent 응답 본문이 비어 있습니다.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            AgentErrorResponse error = errorResponse(exception);
            throw new AgentClientException(errorCode(error, exception), errorMessage(error, exception), exception);
        } catch (RestClientException exception) {
            throw new AgentClientException(
                    "PROVIDER_UNAVAILABLE", "Agent에 연결할 수 없습니다.", exception);
        }
    }

    private AgentErrorResponse errorResponse(RestClientResponseException exception) {
        try {
            return exception.getResponseBodyAs(AgentErrorResponse.class);
        } catch (RuntimeException ignored) {
            // 오류 본문 자체가 계약을 어겼으면 HTTP status 기반 코드로 기록한다.
            return null;
        }
    }

    private String errorCode(AgentErrorResponse response, RestClientResponseException exception) {
        if (response != null && response.error() != null
                && StringUtils.hasText(response.error().code())) {
            return response.error().code();
        }
        return exception.getStatusCode().value() == 401
                ? "UNAUTHORIZED"
                : "AGENT_HTTP_" + exception.getStatusCode().value();
    }

    private String errorMessage(AgentErrorResponse response, RestClientResponseException exception) {
        if (response == null || response.error() == null) {
            return "Agent가 오류 응답을 반환했습니다. status="
                    + exception.getStatusCode().value()
                    + " body="
                    + truncateBody(exception.getResponseBodyAsString());
        }
        String message = StringUtils.hasText(response.error().message())
                ? response.error().message()
                : "Agent가 오류 응답을 반환했습니다.";
        if (response.error().details() == null) {
            return message;
        }
        return message + " details=" + response.error().details();
    }

    private String truncateBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_ERROR_BODY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_BODY_LENGTH);
    }
}
