package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.dto.AgentErrorResponse;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.dto.AgentSelfCritiqueResponse;
import com.example.be.global.config.RestClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

@Component
public class AgentClient {

    static final String AGENT_TOKEN_HEADER = "X-Agent-Token";
    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final RestClient analyzeClient;
    private final RestClient insightClient;
    private final RestClient reportClient;

    @Autowired
    public AgentClient(RestClientFactory restClientFactory, AgentProperties properties) {
        this(
                restClientFactory.create(
                        properties.getConnectTimeout(), properties.getAnalyzeTimeout()),
                restClientFactory.create(
                        properties.getConnectTimeout(), properties.getInsightTimeout()),
                restClientFactory.create(
                        properties.getConnectTimeout(), properties.getReportTimeout()),
                properties);
    }

    AgentClient(RestClient.Builder builder, AgentProperties properties) {
        this(builder, builder, builder, properties);
    }

    AgentClient(RestClient.Builder analyzeBuilder,
                RestClient.Builder insightBuilder,
                RestClient.Builder reportBuilder,
                AgentProperties properties) {
        validateSecureBaseUrl(properties);
        this.analyzeClient = configured(analyzeBuilder, properties).build();
        this.insightClient = configured(insightBuilder, properties).build();
        this.reportClient = configured(reportBuilder, properties).build();
    }

    private RestClient.Builder configured(RestClient.Builder builder, AgentProperties properties) {
        RestClient.Builder configured = builder.baseUrl(properties.getBaseUrl());
        if (StringUtils.hasText(properties.getToken())) {
            configured.defaultHeader(AGENT_TOKEN_HEADER, properties.getToken());
        }
        return configured;
    }

    private void validateSecureBaseUrl(AgentProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        URI baseUrl;
        try {
            baseUrl = URI.create(properties.getBaseUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("news.agent.base-url 형식이 올바르지 않습니다.", exception);
        }
        String scheme = baseUrl.getScheme();
        String host = baseUrl.getHost();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(host)) {
            return;
        }
        throw new IllegalStateException(
                "활성 Agent base URL은 HTTPS여야 하며 HTTP는 loopback 개발 주소만 허용됩니다.");
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || normalized.startsWith("127.");
    }

    public AgentAnalyzeResponse analyze(AgentAnalyzeRequest request) {
        return post(analyzeClient, "/v1/analyze", request, AgentAnalyzeResponse.class);
    }

    public AgentSelfCritiqueResponse selfCritique(AgentAnalyzeRequest request) {
        return post(analyzeClient, "/v1/analyze", request, AgentSelfCritiqueResponse.class);
    }

    public AgentReportResponse report(AgentReportRequest request) {
        return post(reportClient, "/v1/report", request, AgentReportResponse.class);
    }

    public AgentInsightResponse insight(AgentInsightRequest request) {
        return post(insightClient, "/v1/insight", request, AgentInsightResponse.class);
    }

    public AgentEvidenceResponse verifyEvidence(AgentEvidenceRequest request) {
        return post(analyzeClient, "/v1/verify-evidence", request, AgentEvidenceResponse.class);
    }

    private <T> T post(RestClient client, String uri, Object request, Class<T> responseType) {
        try {
            T response = client.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new AgentClientException("SCHEMA_VIOLATION", "Agent 응답 본문이 비어 있습니다.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            AgentErrorResponse error = errorResponse(exception);
            throw new AgentClientException(
                    errorCode(error, exception),
                    errorMessage(error, exception),
                    exception,
                    error == null ? null : error.usage());
        } catch (RestClientException exception) {
            AgentClientException.TimeoutPhase timeoutPhase = timeoutPhase(exception);
            throw new AgentClientException(
                    "PROVIDER_UNAVAILABLE",
                    timeoutPhase == AgentClientException.TimeoutPhase.READ
                            ? "Agent 응답 대기 시간이 초과되었습니다."
                            : "Agent에 연결할 수 없습니다.",
                    exception,
                    null,
                    timeoutPhase);
        }
    }

    AgentClientException.TimeoutPhase timeoutPhase(RestClientException exception) {
        if (!(exception instanceof ResourceAccessException)) {
            return AgentClientException.TimeoutPhase.NONE;
        }
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof HttpConnectTimeoutException) {
                return AgentClientException.TimeoutPhase.CONNECT;
            }
            if (cause instanceof HttpTimeoutException) {
                return AgentClientException.TimeoutPhase.READ;
            }
            if (cause instanceof SocketTimeoutException) {
                String message = cause.getMessage();
                return message != null && message.toLowerCase(Locale.ROOT).contains("connect")
                        ? AgentClientException.TimeoutPhase.CONNECT
                        : AgentClientException.TimeoutPhase.READ;
            }
            cause = cause.getCause();
        }
        return AgentClientException.TimeoutPhase.NONE;
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
