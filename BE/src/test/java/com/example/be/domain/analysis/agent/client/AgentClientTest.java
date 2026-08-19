package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentClientTest {

    @Test
    void sendsTokenAndReadsMockAnalyzeContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentProperties properties = properties();
        AgentClient client = new AgentClient(builder, properties);
        server.expect(requestTo("http://127.0.0.1:8088/v1/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AgentClient.AGENT_TOKEN_HEADER, "test-agent-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("run:42:article:10"))
                .andExpect(jsonPath("$.article.bodyText").value("기사 본문"))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        AgentAnalyzeResponse response = client.analyze(request());

        assertEquals("Mock 한국어 요약", response.summaryKo());
        assertEquals(List.of(1), response.sections().getFirst().bullets().getFirst().evidenceSentenceIds());
        assertEquals("mock", response.meta().provider());
        server.verify();
    }

    @Test
    void preservesAgentErrorCodeFromJsonFailureContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentClient client = new AgentClient(builder, properties());
        server.expect(requestTo("http://127.0.0.1:8088/v1/analyze"))
                .andRespond(withStatus(HttpStatus.CONTENT_TOO_LARGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"INPUT_TOO_LARGE","message":"입력이 너무 큽니다."}}
                                """));

        AgentClientException exception = assertThrows(
                AgentClientException.class,
                () -> client.analyze(request()));

        assertEquals("INPUT_TOO_LARGE", exception.getCode());
        server.verify();
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8088");
        properties.setToken("test-agent-token");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setAnalyzeTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private AgentAnalyzeRequest request() {
        return new AgentAnalyzeRequest(
                "run:42:article:10",
                AgentPlan.FREE,
                new AgentAnalyzeRequest.ArticlePayload(
                        10L, "기사", "https://example.com/10", "ko", OffsetDateTime.now(), "기사 본문"),
                new AgentAnalyzeRequest.TopicPayload("HBM", "HBM", List.of("HBM"), List.of(), List.of()),
                null);
    }

    private String responseJson() {
        return """
                {
                  "sentences": ["근거 문장."],
                  "sections": [{
                    "heading": "핵심",
                    "bullets": [{
                      "text": "핵심 주장",
                      "evidenceSentenceIds": [1],
                      "groundedness": "grounded",
                      "confidence": 1.0
                    }]
                  }],
                  "summaryKo": "Mock 한국어 요약",
                  "classification": {
                    "intent": "산업 동향 보도",
                    "sentiment": "neutral",
                    "riskLevel": "low",
                    "relevance": "reference",
                    "category": "제품/공정"
                  },
                  "entities": {"companies": [], "products": [], "technologies": []},
                  "meta": {
                    "provider": "mock",
                    "model": "mock",
                    "promptVersion": "analyze.mock.v1",
                    "inputTokens": 0,
                    "outputTokens": 0,
                    "costUsd": 0,
                    "credits": 0,
                    "mock": true,
                    "truncated": false
                  }
                }
                """;
    }
}
