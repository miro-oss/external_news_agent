package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                                {"error":{"code":"INPUT_TOO_LARGE","message":"입력이 너무 큽니다.","details":[{"loc":["body","article","title"],"msg":"Field required","type":"missing"}]}}
                                """));

        AgentClientException exception = assertThrows(
                AgentClientException.class,
                () -> client.analyze(request()));

        assertEquals("INPUT_TOO_LARGE", exception.getCode());
        assertTrue(exception.getMessage().contains("입력이 너무 큽니다."));
        assertTrue(exception.getMessage().contains("article"));
        assertTrue(exception.getMessage().contains("Field required"));
        server.verify();
    }

    @Test
    void sendsReportContractAndReadsStructuredChannelInputs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentClient client = new AgentClient(builder, properties());
        server.expect(requestTo("http://127.0.0.1:8088/v1/report"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AgentClient.AGENT_TOKEN_HEADER, "test-agent-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("run:42:report"))
                .andExpect(jsonPath("$.sourceStats.stubExcluded").value(2))
                .andRespond(withSuccess(reportResponseJson(), MediaType.APPLICATION_JSON));

        AgentReportResponse response = client.report(reportRequest());

        assertEquals("보고서", response.title());
        assertEquals(List.of("짧은 속보"), response.executiveSummary());
        assertEquals(List.of(501L), response.importantEvents().getFirst().sourceFindingIds());
        assertEquals("report.ko.v1", response.meta().promptVersion());
        server.verify();
    }

    @Test
    void sendsEvidenceContractAndReadsGroundedness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentClient client = new AgentClient(builder, properties());
        server.expect(requestTo("http://127.0.0.1:8088/v1/verify-evidence"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AgentClient.AGENT_TOKEN_HEADER, "test-agent-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("finding:501:verify"))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.claim").value("HBM4 양산 일정이 앞당겨졌다."))
                .andExpect(jsonPath("$.sentences[0].id").value(1))
                .andExpect(jsonPath("$.sentences[0].text").value("HBM4 양산 일정이 앞당겨졌다."))
                .andRespond(withSuccess(evidenceResponseJson(), MediaType.APPLICATION_JSON));

        AgentEvidenceResponse response = client.verifyEvidence(evidenceRequest());

        assertEquals("grounded", response.status());
        assertEquals(List.of(1), response.acceptedSentenceIds());
        assertEquals("evidence.rules.v2", response.meta().promptVersion());
        server.verify();
    }

    @Test
    void preservesUsageFromFailedReportContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentClient client = new AgentClient(builder, properties());
        server.expect(requestTo("http://127.0.0.1:8088/v1/report"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"SCHEMA_VIOLATION","message":"출력 오류","details":{"usage":{"inputTokens":30,"outputTokens":15,"costUsd":0.25,"credits":2},"truncated":true}}}
                                """));

        AgentClientException exception = assertThrows(
                AgentClientException.class,
                () -> client.report(reportRequest()));

        assertEquals(30L, exception.getUsage().inputTokens());
        assertEquals(15L, exception.getUsage().outputTokens());
        assertEquals(0, new java.math.BigDecimal("0.25").compareTo(exception.getUsage().costUsd()));
        assertEquals(0, new java.math.BigDecimal("2").compareTo(exception.getUsage().credits()));
        server.verify();
    }

    @Test
    void includesStatusAndBodyWhenAgentReturnsNonContractError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentClient client = new AgentClient(builder, properties());
        server.expect(requestTo("http://127.0.0.1:8088/v1/analyze"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Invalid HTTP request received."));

        AgentClientException exception = assertThrows(
                AgentClientException.class,
                () -> client.analyze(request()));

        assertEquals("AGENT_HTTP_400", exception.getCode());
        assertTrue(exception.getMessage().contains("status=400"));
        assertTrue(exception.getMessage().contains("Invalid HTTP request received."));
        server.verify();
    }

    @Test
    void distinguishesConnectTimeoutFromReadTimeout() {
        AgentClient client = new AgentClient(RestClient.builder(), properties());

        assertEquals(
                AgentClientException.TimeoutPhase.CONNECT,
                client.timeoutPhase(new ResourceAccessException(
                        "connect", new HttpConnectTimeoutException("connect timed out"))));
        assertEquals(
                AgentClientException.TimeoutPhase.READ,
                client.timeoutPhase(new ResourceAccessException(
                        "read", new SocketTimeoutException("Read timed out"))));
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8088");
        properties.setToken("test-agent-token");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setAnalyzeTimeout(Duration.ofSeconds(1));
        properties.setReportTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private AgentReportRequest reportRequest() {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-08-10T09:00:00+09:00");
        return new AgentReportRequest(
                "run:42:report",
                AgentPlan.FREE,
                new AgentReportRequest.RunPayload(
                        42L, startedAt, startedAt.plusMinutes(3), List.of("HBM")),
                List.of(new AgentReportRequest.FindingPayload(
                        501L,
                        10L,
                        "기사",
                        "https://example.com/10",
                        "Example",
                        "NEW",
                        "한국어 요약",
                        List.of("핵심"),
                        "발표",
                        "neutral",
                        "low",
                        "important",
                        "제품/공정",
                        "FULLTEXT")),
                List.of(),
                new AgentReportRequest.SourceStatsPayload(3, 0, 0, 0, 2),
                List.of("STUB 2건 제외"),
                "TECHNOLOGY");
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

    private AgentEvidenceRequest evidenceRequest() {
        return new AgentEvidenceRequest(
                "finding:501:verify",
                AgentPlan.FREE,
                "HBM4 양산 일정이 앞당겨졌다.",
                List.of(new AgentEvidenceRequest.SentencePayload(
                        1, "HBM4 양산 일정이 앞당겨졌다.")));
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

    private String reportResponseJson() {
        return """
                {
                  "title": "보고서",
                  "executiveSummary": ["짧은 속보"],
                  "importantEvents": [{
                    "title": "중요 기사",
                    "summaryKo": "한국어 요약",
                    "significance": "즉시 확인 필요",
                    "sourceFindingIds": [501]
                  }],
                  "watchItems": [],
                  "sourceNotes": ["제외 사항 없음"],
                  "markdownBody": "# 보고서",
                  "meta": {
                    "provider": "gemini",
                    "model": "configured-model",
                    "promptVersion": "report.ko.v1",
                    "inputTokens": 100,
                    "outputTokens": 20,
                    "costUsd": 0,
                    "credits": 0,
                    "mock": false,
                    "truncated": false
                  }
                }
                """;
    }

    private String evidenceResponseJson() {
        return """
                {
                  "status": "grounded",
                  "acceptedSentenceIds": [1],
                  "reason": "주장이 근거 문장에 직접 나타납니다.",
                  "meta": {
                    "provider": "mock",
                    "model": "evidence-rules-v2",
                    "promptVersion": "evidence.rules.v2",
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
