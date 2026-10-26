package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceRequest;
import com.example.be.domain.analysis.agent.dto.AgentErrorResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AgentAdmissionClientTest {
    private static final String BUSY = """
            {"error":{"code":"PROVIDER_UNAVAILABLE","message":"busy","details":{
              "concurrencyLimited":true,"requestNotStarted":true,
              "admission":{"scope":"REQUEST","reason":"CAPACITY","providerInvoked":false,"retryable":true}}}}
            """;

    @Test void retriesOnlyUnstartedRelevanceWithBoundedDelayAndTheSameKey() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var delays = new ArrayList<Long>();
        var client = new AgentClient(builder, properties(), delays::add);
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo("http://127.0.0.1:8088/v1/topic-relevance"))
                    .andExpect(jsonPath("$.idempotencyKey").value("same-key"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(BUSY));
        }
        server.expect(requestTo("http://127.0.0.1:8088/v1/topic-relevance"))
                .andExpect(jsonPath("$.idempotencyKey").value("same-key"))
                .andRespond(withSuccess("{\"decisions\":[],\"meta\":null}", MediaType.APPLICATION_JSON));
        assertNotNull(client.topicRelevance(request()));
        assertEquals(List.of(1000L, 2000L), delays);
        server.verify();
    }

    @Test void saturatedRelevanceStopsAfterThreeAttempts() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var delays = new ArrayList<Long>();
        var client = new AgentClient(builder, properties(), delays::add);
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo("http://127.0.0.1:8088/v1/topic-relevance"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(BUSY));
        }
        assertTrue(assertThrows(AgentClientException.class,
                () -> client.topicRelevance(request())).isRequestAdmissionRejected());
        assertEquals(List.of(1000L, 2000L), delays);
        server.verify();
    }

    @Test void legacyLastAttemptFlagsAndUncertainUsageNeverPermitRetry() {
        for (String body : List.of(
                "{\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\",\"message\":\"busy\",\"details\":{\"concurrencyLimited\":true,\"requestNotStarted\":true}}}",
                BUSY.replace("\"admission\":", "\"usage\":{\"inputTokens\":2,\"outputTokens\":0,\"costUsd\":0,\"credits\":0},\"admission\":"),
                BUSY.replace("\"admission\":", "\"usage\":{\"credits\":0},\"admission\":"))) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new AgentClient(builder, properties(), delay -> fail("Unsafe retry"));
            server.expect(requestTo("http://127.0.0.1:8088/v1/topic-relevance"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(body));
            assertFalse(assertThrows(AgentClientException.class,
                    () -> client.topicRelevance(request())).isRequestAdmissionRejected());
            server.verify();
        }
    }

    @Test void otherEndpointsDoNotTrustRelevanceAttestation() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AgentClient(builder, properties(), delay -> fail("Other endpoint retry"));
        server.expect(requestTo("http://127.0.0.1:8088/v1/analyze"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(BUSY));
        var request = new com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest(
                "analysis-key", AgentPlan.FREE, null, List.of(), null, null);
        assertFalse(assertThrows(AgentClientException.class, () -> client.analyze(request)).isRequestAdmissionRejected());
        server.verify();
    }

    @Test void interruptedAdmissionWaitPreservesInterruptAndStopsRequests() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new AgentClient(builder, properties(), delay -> { throw new InterruptedException(); });
        server.expect(requestTo("http://127.0.0.1:8088/v1/topic-relevance"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(BUSY));
        try {
            assertTrue(assertThrows(AgentClientException.class,
                    () -> client.topicRelevance(request())).isRequestAdmissionRejected());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        server.verify();
    }

    @Test void attestationRequiresExactTypesAndWholeRequestScope() {
        var valid = Map.<String, Object>of("scope", "REQUEST", "reason", "CAPACITY", "providerInvoked", false, "retryable", true);
        assertNotNull(error(Map.of("admission", valid)).admissionRejection());
        for (var change : Map.<String, Object>of("scope", "ATTEMPT", "reason", "TIMEOUT", "providerInvoked", "false", "retryable", "true", "extra", true).entrySet()) {
            var invalid = new HashMap<>(valid);
            invalid.put(change.getKey(), change.getValue());
            assertNull(error(Map.of("admission", invalid)).admissionRejection());
        }
        assertNull(error(Map.of("admission", valid, "usage", "unknown")).admissionRejection());
        assertNull(error(Map.of("admission", valid, "usage", Map.of("inputTokens", "not-a-number"))).admissionRejection());
    }

    private AgentErrorResponse error(Object details) {
        return new AgentErrorResponse(new AgentErrorResponse.ErrorDetail("PROVIDER_UNAVAILABLE", "busy", details));
    }
    private AgentProperties properties() {
        var properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8088");
        return properties;
    }
    private AgentTopicRelevanceRequest request() {
        return new AgentTopicRelevanceRequest("same-key", AgentPlan.FREE,
                new AgentTopicRelevanceRequest.TopicInput(7L, "topic", "query", List.of(), List.of(), List.of()),
                List.of(new AgentTopicRelevanceRequest.ArticleInput(1L, "title", "summary", "body")));
    }
}
