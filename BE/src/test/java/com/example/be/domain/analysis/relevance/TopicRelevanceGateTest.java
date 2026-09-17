package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceRequest;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TopicRelevanceGateTest {
    @Mock AgentClient client;
    @Mock AgentQuotaService quota;
    @Mock TopicRelevanceStore store;
    @Mock TopicRelevanceFinalizer finalizer;
    @Mock CollectionResultWriter writer;
    AgentProperties properties;
    TopicRelevanceGate gate;

    @BeforeEach void setup() {
        properties = new AgentProperties();
        properties.setEnabled(true);
        gate = new TopicRelevanceGate(properties, client, quota, store, finalizer, writer, new ObjectMapper());
    }

    @Test void batchesAndAcceptsOnlyExplicitlyRelevantDecisions() {
        reserve();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), a.articleId() % 2 == 0 ? "RELEVANT" : "IRRELEVANT", "주제 맥락 판정", List.of(a.title()))).toList());
        });
        var candidates = LongStream.rangeClosed(1, 11).mapToObj(id -> candidate(id, 7L)).toList();
        var accepted = gate.assess(42L, AgentPlan.FREE, candidates);
        assertEquals(Set.of(new TopicRelevanceGate.Key(2L, 7L), new TopicRelevanceGate.Key(4L, 7L),
                new TopicRelevanceGate.Key(6L, 7L), new TopicRelevanceGate.Key(8L, 7L), new TopicRelevanceGate.Key(10L, 7L)), accepted);
        verify(client, times(2)).topicRelevance(any());
        var order = inOrder(store, client, finalizer);
        order.verify(store).findByRun(42L);
        order.verify(store).saveAll(argThat(rows -> rows.size() == 10 && rows.stream().allMatch(a -> a.status() == TopicRelevanceStatus.UNCERTAIN)));
        order.verify(client).topicRelevance(any());
        order.verify(finalizer).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
    }

    @Test void disabledAgentHoldsInsteadOfPretendingRelated() {
        properties.setEnabled(false);
        assertTrue(gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L))).isEmpty());
        verifyNoInteractions(client, quota, finalizer);
        verify(writer).addAgentWarning(eq(42L), eq("TOPIC_RELEVANCE_UNCERTAIN"), anyString());
    }

    @Test void quotaExhaustionIsHeldAndNeverCallsProvider() {
        when(quota.reserve(anyLong(), anyString(), eq(AgentTask.TOPIC_RELEVANCE), any()))
                .thenThrow(new QuotaExceededException(AgentPlan.FREE, "예산 부족"));
        assertTrue(gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L))).isEmpty());
        verifyNoInteractions(client, finalizer);
    }

    @Test void readTimeoutIsAuditedWithOriginalTimeoutForQuotaSettlement() {
        reserve();
        var failure = new AgentClientException("PROVIDER_UNAVAILABLE", "timeout", null, null, AgentClientException.TimeoutPhase.READ);
        when(client.topicRelevance(any())).thenThrow(failure);
        assertTrue(gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L))).isEmpty());
        verify(finalizer).failure(eq(42L), any(), argThat(rows -> rows.getFirst().status() == TopicRelevanceStatus.UNCERTAIN),
                anyString(), any(), any(), same(failure));
    }

    @Test void invalidGroundingKeepsObservedUsageInFailure() {
        reserve();
        when(client.topicRelevance(any())).thenReturn(response(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "RELEVANT", "관련", List.of("기사에 없는 인용")))));
        assertTrue(gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L))).isEmpty());
        ArgumentCaptor<AgentClientException> failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(finalizer).failure(eq(42L), any(), anyList(), anyString(), any(), any(), failure.capture());
        assertEquals(10L, failure.getValue().getUsage().inputTokens());
        verify(finalizer, never()).success(any(), any(), any(), anyList(), anyString(), any(), any());
    }

    @Test void sameRunCacheIsBoundToTopicAndFullBodyEvenBeyondExcerpt() {
        reserve();
        Map<TopicRelevanceGate.Key, TopicRelevanceStore.Assessment> saved = new HashMap<>();
        when(store.findByRun(42L)).thenAnswer(invocation -> new ArrayList<>(saved.values()));
        doAnswer(invocation -> {
            List<TopicRelevanceStore.Assessment> values = invocation.getArgument(3);
            values.forEach(a -> saved.put(new TopicRelevanceGate.Key(a.articleId(), a.topicId()), a));
            return null;
        }).when(finalizer).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), "RELEVANT", "관련", List.of(a.title()))).toList());
        });
        var input = candidate(1L, 7L, "본문".repeat(3000));
        assertEquals(1, gate.assess(42L, AgentPlan.FREE, List.of(input)).size());
        assertEquals(1, gate.assess(42L, AgentPlan.FREE, List.of(input)).size());
        verify(client, times(1)).topicRelevance(any());
        gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L, "본문".repeat(3000) + "수정")));
        gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 8L)));
        verify(client, times(3)).topicRelevance(any());
    }

    @Test void responseRequiresExactIdsAndForbidsMockAcceptance() {
        var request = new AgentTopicRelevanceRequest("test", AgentPlan.FREE,
                new AgentTopicRelevanceRequest.TopicInput(7L, "장비", "반도체 장비", List.of(), List.of(), List.of()),
                List.of(new AgentTopicRelevanceRequest.ArticleInput(1L, "공정위", null, "토스 분쟁")));
        assertThrows(AgentClientException.class, () -> TopicRelevanceGate.validate(response(List.of(
                new AgentTopicRelevanceResponse.Decision(2L, "IRRELEVANT", "무관", List.of("공정위")))), request));
        var mockMeta = new AgentTopicRelevanceResponse.Meta("mock", "mock", TopicRelevanceGate.PROMPT_VERSION,
                0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, true, false);
        assertThrows(AgentClientException.class, () -> TopicRelevanceGate.validate(new AgentTopicRelevanceResponse(
                List.of(new AgentTopicRelevanceResponse.Decision(1L, "RELEVANT", "관련", List.of("공정위"))), mockMeta), request));
        assertDoesNotThrow(() -> TopicRelevanceGate.validate(new AgentTopicRelevanceResponse(
                List.of(new AgentTopicRelevanceResponse.Decision(1L, "UNCERTAIN", "모의 판정", List.of())), mockMeta), request));
        var actualMeta = new AgentTopicRelevanceResponse.Meta("openai", "model", TopicRelevanceGate.PROMPT_VERSION,
                1L, 1L, new BigDecimal("0.0000004"), new BigDecimal("0.00001"), false, false);
        assertDoesNotThrow(() -> TopicRelevanceGate.validate(new AgentTopicRelevanceResponse(
                List.of(new AgentTopicRelevanceResponse.Decision(1L, "IRRELEVANT", "금융 분쟁", List.of("토스 분쟁"))), actualMeta), request));
    }

    @Test void oversizedBatchSplitsBeforeProviderInsteadOfDiscardingValidArticles() {
        reserve();
        var keywords = java.util.stream.IntStream.range(0, 100).mapToObj(i -> ("k" + i).repeat(100).substring(0, 100)).toList();
        Topic topic = Topic.builder().id(7L).name("장비").queryText("반도체 장비")
                .requiredKeywords(keywords).optionalKeywords(keywords).excludedKeywords(keywords).build();
        var candidates = LongStream.rangeClosed(1, 10).mapToObj(id -> new TopicRelevanceGate.Candidate(
                Article.builder().id(id).title("제목".repeat(500)).summary("요약".repeat(500))
                        .storedBody(ArticleBody.of("본문".repeat(2500))).fetchStatus(FetchStatus.FULLTEXT).topic(topic).build(), topic)).toList();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            assertTrue(new ObjectMapper().writeValueAsString(request).length() <= 85_000);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), "UNCERTAIN", "판단 보류", List.of())).toList());
        });
        assertTrue(gate.assess(42L, AgentPlan.FREE, candidates).isEmpty());
        verify(client, times(2)).topicRelevance(any());
    }

    @Test void malformedUsageCannotOverflowFailureAudit() {
        reserve();
        var unsafe = new AgentTopicRelevanceResponse.Meta("openai", "test-model", TopicRelevanceGate.PROMPT_VERSION,
                -1L, 2L, new BigDecimal("1e50"), new BigDecimal("1e50"), false, false);
        when(client.topicRelevance(any())).thenReturn(new AgentTopicRelevanceResponse(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "UNCERTAIN", "보류", List.of())), unsafe));
        assertTrue(gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L))).isEmpty());
        ArgumentCaptor<AgentClientException> failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(finalizer).failure(eq(42L), any(), anyList(), anyString(), any(), any(), failure.capture());
        assertNull(failure.getValue().getUsage().inputTokens());
        assertNull(failure.getValue().getUsage().costUsd());
        assertNull(failure.getValue().getUsage().credits());
        assertEquals(2L, failure.getValue().getUsage().outputTokens());
    }

    @Test void warningWriteFailureDoesNotResettleSuccessfulMixedBatch() {
        reserve();
        when(client.topicRelevance(any())).thenReturn(response(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "RELEVANT", "관련", List.of("기사 1")),
                new AgentTopicRelevanceResponse.Decision(2L, "UNCERTAIN", "근거 부족", List.of()))));
        doThrow(new IllegalStateException("warning storage failed")).when(writer)
                .addAgentWarning(eq(42L), eq("TOPIC_RELEVANCE_UNCERTAIN"), anyString());

        assertEquals(Set.of(new TopicRelevanceGate.Key(1L, 7L)),
                gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L), candidate(2L, 7L))));
        verify(finalizer).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        verify(finalizer, never()).failure(any(), any(), anyList(), anyString(), any(), any(), any());
    }

    @Test void angleBracketExpansionIsIncludedInBatchLimit() {
        reserve();
        var candidates = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> candidate(id, 7L, "<>".repeat(2500))).toList();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            String serialized = new ObjectMapper().writeValueAsString(request);
            long expandedLength = serialized.length()
                    + 5L * serialized.chars().filter(c -> c == '<' || c == '>').count();
            assertTrue(expandedLength <= 85_000);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), "UNCERTAIN", "판단 보류", List.of())).toList());
        });

        assertTrue(gate.assess(42L, AgentPlan.FREE, candidates).isEmpty());
        verify(client, atLeast(2)).topicRelevance(any());
    }

    private void reserve() {
        when(quota.reserve(anyLong(), anyString(), eq(AgentTask.TOPIC_RELEVANCE), any())).thenAnswer(i ->
                new QuotaReservation(1L, i.getArgument(0), i.getArgument(1), AgentTask.TOPIC_RELEVANCE, i.getArgument(3), BigDecimal.ONE));
    }
    private TopicRelevanceGate.Candidate candidate(Long id, Long topicId) { return candidate(id, topicId, "반도체 제조 장비 공정 본문"); }
    private TopicRelevanceGate.Candidate candidate(Long id, Long topicId, String body) {
        Topic topic = Topic.builder().id(topicId).name("제조장비").queryText("반도체 장비 공정").build();
        Article article = Article.builder().id(id).title("기사 " + id).topic(topic).fetchStatus(FetchStatus.FULLTEXT)
                .storedBody(ArticleBody.of(body)).build();
        return new TopicRelevanceGate.Candidate(article, topic);
    }
    private AgentTopicRelevanceResponse response(List<AgentTopicRelevanceResponse.Decision> decisions) {
        return new AgentTopicRelevanceResponse(decisions, new AgentTopicRelevanceResponse.Meta("openai", "test-model",
                TopicRelevanceGate.PROMPT_VERSION, 10L, 5L, BigDecimal.ZERO, BigDecimal.ZERO, false, false));
    }
}
