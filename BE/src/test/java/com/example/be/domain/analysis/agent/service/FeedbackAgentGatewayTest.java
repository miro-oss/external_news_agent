package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.*;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackAgentGatewayTest {
    private final AgentClient client = mock(AgentClient.class);
    private final AgentQuotaService quota = mock(AgentQuotaService.class);
    private final AgentRunJdbcRepository runs = mock(AgentRunJdbcRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AgentProperties properties = enabledProperties();
    private final FeedbackAgentGateway gateway = new FeedbackAgentGateway(client,quota,runs,transactions,properties);
    private final JsonMapper json = new JsonMapper();

    private static AgentProperties enabledProperties() {
        var value = new AgentProperties(); value.setEnabled(true); return value;
    }

    @Test void disabledAgentNeverReservesOrCallsProvider() {
        properties.setEnabled(false);
        assertThrows(AgentClientException.class,()->gateway.review(null,2L,
                json.readTree("{\"idempotencyKey\":\"disabled\",\"plan\":\"FREE\"}")));
        verifyNoInteractions(quota,client,runs);
    }

    @Test void reservesBeforeCallAndRecordsUsageWithoutFeedbackText() {
        var request = json.readTree("{\"idempotencyKey\":\"feedback-review:1:attempt:1\",\"plan\":\"FREE\",\"feedback\":{\"comment\":\"개인 제보\"}}");
        var reservation = new QuotaReservation(1L,null,"feedback-review:1:attempt:1",AgentTask.FEEDBACK_REVIEW,AgentPlan.FREE,BigDecimal.ONE);
        when(quota.reserve(isNull(),anyString(),eq(AgentTask.FEEDBACK_REVIEW),eq(AgentPlan.FREE))).thenReturn(reservation);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var response = json.readTree("""
                {"verdict":"INSUFFICIENT_EVIDENCE","diagnosis":"근거가 충분하지 않습니다.","evidence":[],"proposedPolicy":null,"meta":{"provider":"openai","model":"gpt-5.4-nano",
                "promptVersion":"feedback-review.ko.v1","inputTokens":123,"outputTokens":45,
                "costUsd":0.0001,"credits":0,"mock":false,"truncated":false}}
                """);
        when(client.feedbackReview(request)).thenReturn(response);
        assertSame(response,gateway.review(null,2L,request));
        var order=inOrder(quota,client,runs);
        order.verify(quota).reserve(isNull(),anyString(),eq(AgentTask.FEEDBACK_REVIEW),eq(AgentPlan.FREE));
        order.verify(client).feedbackReview(request);
        var audit=ArgumentCaptor.forClass(AgentRun.class);
        order.verify(runs).insertIfAbsent(audit.capture());
        order.verify(quota).completeSuccess(eq(reservation),eq(new BigDecimal("0.000000")));
        assertEquals(AgentTask.FEEDBACK_REVIEW,audit.getValue().getAgentTask());
        assertEquals(123L,audit.getValue().getInputTokens());
        assertEquals(64,audit.getValue().getRequestHash().length());
        assertNull(audit.getValue().getActionPayload());
    }

    @Test void exhaustedQuotaDoesNotInvokeAgent() {
        when(quota.reserve(any(),anyString(),any(),any())).thenThrow(new IllegalStateException("quota exhausted"));
        assertThrows(IllegalStateException.class,()->gateway.evaluate(null,4L,
                json.readTree("{\"idempotencyKey\":\"evaluation:1\",\"plan\":\"FREE\"}")));
        verifyNoInteractions(client);
    }

    @Test void malformedResponseStillSettlesObservedUsage() {
        var request=json.readTree("{\"idempotencyKey\":\"evaluation:1\",\"plan\":\"PAID\"}");
        var reservation=new QuotaReservation(1L,null,"evaluation:1",AgentTask.FEEDBACK_EVALUATE,AgentPlan.PAID,new BigDecimal("5"));
        when(quota.reserve(any(),anyString(),any(),any())).thenReturn(reservation);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(client.feedbackEvaluate(request)).thenReturn(json.readTree("""
                {"meta":{"provider":"openai","model":"test","inputTokens":10,"outputTokens":2,
                "costUsd":0.1,"credits":0.5,"mock":false,"truncated":true}}
                """));
        assertThrows(AgentClientException.class,()->gateway.evaluate(null,4L,request));
        var failure=ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeFailure(eq(reservation),failure.capture());
        assertEquals(new BigDecimal("0.500000"),failure.getValue().getUsage().credits());
        var audit=ArgumentCaptor.forClass(AgentRun.class);
        verify(runs).insertIfAbsent(audit.capture());
        assertEquals(AgentRunStatus.FAILED,audit.getValue().getStatus());
    }

    @Test void missingUsageAfterResponseConsumesConservativeReservation() {
        var request=json.readTree("{\"idempotencyKey\":\"evaluation:2\",\"plan\":\"FREE\"}");
        var reservation=new QuotaReservation(1L,null,"evaluation:2",AgentTask.FEEDBACK_EVALUATE,AgentPlan.FREE,BigDecimal.ONE);
        when(quota.reserve(any(),anyString(),any(),any())).thenReturn(reservation);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(client.feedbackEvaluate(request)).thenReturn(json.readTree("{\"meta\":{}}"));
        assertThrows(AgentClientException.class,()->gateway.evaluate(null,4L,request));
        verify(quota).completeFailure(reservation,"FEEDBACK_USAGE_UNKNOWN");
    }
}
