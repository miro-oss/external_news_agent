package com.example.be.news.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

@Component
public class AgentClient {

    static final String AGENT_TOKEN_HEADER = "X-Agent-Token";

    private final RestClient restClient;

    @Autowired
    public AgentClient(AgentProperties properties) {
        this(restClientBuilder(properties), properties);
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
            String code = errorCode(exception);
            throw new AgentClientException(code, "Agent가 오류 응답을 반환했습니다.", exception);
        } catch (RestClientException exception) {
            throw new AgentClientException(
                    "PROVIDER_UNAVAILABLE", "Agent에 연결할 수 없습니다.", exception);
        }
    }

    private String errorCode(RestClientResponseException exception) {
        try {
            AgentErrorResponse response = exception.getResponseBodyAs(AgentErrorResponse.class);
            if (response != null && response.error() != null
                    && StringUtils.hasText(response.error().code())) {
                return response.error().code();
            }
        } catch (RuntimeException ignored) {
            // 오류 본문 자체가 계약을 어겼으면 HTTP status 기반 코드로 기록한다.
        }
        return exception.getStatusCode().value() == 401
                ? "UNAUTHORIZED"
                : "AGENT_HTTP_" + exception.getStatusCode().value();
    }

    private static RestClient.Builder restClientBuilder(AgentProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getAnalyzeTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
