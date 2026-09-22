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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @Test void isolatesEachArticleAndAcceptsOnlyExplicitlyRelevantDecisions() {
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
        ArgumentCaptor<AgentTopicRelevanceRequest> requests = ArgumentCaptor.forClass(AgentTopicRelevanceRequest.class);
        verify(client, times(11)).topicRelevance(requests.capture());
        assertTrue(requests.getAllValues().stream().allMatch(request -> request.articles().size() == 1));
        assertEquals(11, requests.getAllValues().stream().map(AgentTopicRelevanceRequest::idempotencyKey).distinct().count());
        verify(quota, times(11)).reserve(eq(42L), anyString(), eq(AgentTask.TOPIC_RELEVANCE), eq(AgentPlan.FREE));
        verify(finalizer, times(11)).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        var order = inOrder(store, client, finalizer);
        order.verify(store).findByRun(42L);
        order.verify(store).saveAll(argThat(rows -> rows.size() == 1 && rows.getFirst().articleId().equals(1L)
                && rows.getFirst().status() == TopicRelevanceStatus.UNCERTAIN));
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

    @Test void oneArticleFailureDoesNotDiscardOrBlockOtherArticles() {
        reserve();
        var timeout = new AgentClientException("PROVIDER_UNAVAILABLE", "timeout", null, null,
                AgentClientException.TimeoutPhase.READ);
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            assertEquals(1, request.articles().size());
            var article = request.articles().getFirst();
            if (article.articleId() == 2L) throw timeout;
            return response(List.of(new AgentTopicRelevanceResponse.Decision(article.articleId(),
                    "RELEVANT", "관련", List.of(article.title()))));
        });

        assertEquals(Set.of(new TopicRelevanceGate.Key(1L, 7L), new TopicRelevanceGate.Key(3L, 7L)),
                gate.assess(42L, AgentPlan.FREE,
                        List.of(candidate(1L, 7L), candidate(2L, 7L), candidate(3L, 7L))));
        verify(client, times(3)).topicRelevance(any());
        verify(finalizer, times(2)).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        verify(finalizer).failure(eq(42L), argThat(request -> request.articles().size() == 1
                        && request.articles().getFirst().articleId() == 2L),
                argThat(rows -> rows.size() == 1 && rows.getFirst().articleId() == 2L
                        && rows.getFirst().status() == TopicRelevanceStatus.UNCERTAIN),
                anyString(), any(), any(), same(timeout));
    }

    @Test void exhaustedQuotaAfterOneArticlePreservesItsDecisionAndStopsFurtherProviderCalls() {
        when(quota.reserve(anyLong(), anyString(), eq(AgentTask.TOPIC_RELEVANCE), any()))
                .thenAnswer(invocation -> new QuotaReservation(1L, invocation.getArgument(0),
                        invocation.getArgument(1), AgentTask.TOPIC_RELEVANCE,
                        invocation.getArgument(3), BigDecimal.ONE))
                .thenThrow(new QuotaExceededException(AgentPlan.FREE, "예산 부족"));
        when(client.topicRelevance(any())).thenReturn(response(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "RELEVANT", "관련", List.of("기사 1")))));

        assertEquals(Set.of(new TopicRelevanceGate.Key(1L, 7L)), gate.assess(42L, AgentPlan.FREE,
                List.of(candidate(1L, 7L), candidate(2L, 7L), candidate(3L, 7L))));
        verify(client).topicRelevance(any());
        verify(finalizer).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        verify(finalizer, never()).failure(any(), any(), anyList(), anyString(), any(), any(), any());
        verify(store, atLeastOnce()).saveAll(argThat(rows -> rows.size() == 1
                && rows.getFirst().articleId() == 3L && rows.getFirst().status() == TopicRelevanceStatus.UNCERTAIN));
    }

    @Test void oversizedSingleArticleInputIsHeldBeforeReservingQuota() {
        var keywords = java.util.Collections.nCopies(100, "<>".repeat(50));
        Topic topic = Topic.builder().id(7L).name("장비").queryText("반도체 장비")
                .requiredKeywords(keywords).optionalKeywords(keywords).excludedKeywords(keywords).build();
        Article article = Article.builder().id(1L).title("기사 1").topic(topic)
                .fetchStatus(FetchStatus.FULLTEXT).storedBody(ArticleBody.of("반도체 공정 본문")).build();

        assertTrue(gate.assess(42L, AgentPlan.FREE,
                List.of(new TopicRelevanceGate.Candidate(article, topic))).isEmpty());
        verifyNoInteractions(client, quota, finalizer);
        verify(writer).addAgentWarning(eq(42L), eq("TOPIC_RELEVANCE_UNCERTAIN"), contains("입력 상한"));
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

    @ParameterizedTest
    @EnumSource(value = TopicRelevanceStatus.class, names = {"RELEVANT", "IRRELEVANT"})
    void v9ReassessesCachedV8DecisionForUnchangedInput(TopicRelevanceStatus cachedStatus) {
        assertEquals("topic-relevance.ko.v9", TopicRelevanceGate.PROMPT_VERSION);
        reserve();
        var input = candidate(1L, 7L);
        var topic = new AgentTopicRelevanceRequest.TopicInput(7L, "제조장비", "반도체 장비 공정",
                List.of(), List.of(), List.of());
        String legacyInput = new ObjectMapper().writeValueAsString(topic)
                + "\ntopic-relevance.ko.v8\nFREE\n\n\n기사 1\nnull\n반도체 제조 장비 공정 본문";
        String legacyHash = TopicRelevanceGate.hash(legacyInput);
        String upgradedHash = TopicRelevanceGate.hash(new ObjectMapper().writeValueAsString(topic)
                + "\ntopic-relevance.ko.v9\nFREE\ngpt-5.6-terra\n기사 1\nnull\n반도체 제조 장비 공정 본문");
        when(store.findByRun(42L)).thenReturn(List.of(new TopicRelevanceStore.Assessment(
                42L, 7L, 1L, cachedStatus, "이전 판정", legacyHash, "topic-relevance.ko.v8",
                "test-model", "[\"기사 1\"]")));
        TopicRelevanceStatus newStatus = cachedStatus == TopicRelevanceStatus.RELEVANT
                ? TopicRelevanceStatus.IRRELEVANT : TopicRelevanceStatus.RELEVANT;
        when(client.topicRelevance(any())).thenReturn(response(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, newStatus.name(), "재검토한 판정", List.of("기사 1")))));

        Set<TopicRelevanceGate.Key> expected = newStatus == TopicRelevanceStatus.RELEVANT
                ? Set.of(new TopicRelevanceGate.Key(1L, 7L)) : Set.of();
        assertEquals(expected, gate.assess(42L, AgentPlan.FREE, List.of(input)));

        verify(client).topicRelevance(any());
        verify(finalizer).success(eq(42L), any(), any(), argThat(rows -> rows.size() == 1
                        && rows.getFirst().status() == newStatus
                        && rows.getFirst().promptVersion().equals("topic-relevance.ko.v9")
                        && rows.getFirst().inputHash().equals(upgradedHash)),
                anyString(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(AgentPlan.class)
    void sameRunCacheDependsOnlyOnTheRelevanceModelForItsPlan(AgentPlan plan) {
        reserve();
        properties.setFreeModel("global-free-a");
        properties.setRelevanceFreeModel("relevance-free-a");
        properties.setPaidModel("paid-a");
        Map<TopicRelevanceGate.Key, TopicRelevanceStore.Assessment> saved = new HashMap<>();
        List<String> persistedHashes = new ArrayList<>();
        when(store.findByRun(42L)).thenAnswer(invocation -> new ArrayList<>(saved.values()));
        doAnswer(invocation -> {
            List<TopicRelevanceStore.Assessment> values = invocation.getArgument(3);
            values.forEach(a -> {
                saved.put(new TopicRelevanceGate.Key(a.articleId(), a.topicId()), a);
                persistedHashes.add(a.inputHash());
            });
            return null;
        }).when(finalizer).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        when(client.topicRelevance(any())).thenReturn(response(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "RELEVANT", "관련", List.of("기사 1")))));
        var input = List.of(candidate(1L, 7L));
        var expected = Set.of(new TopicRelevanceGate.Key(1L, 7L));

        assertEquals(expected, gate.assess(42L, plan, input));
        properties.setFreeModel("global-free-b");
        assertEquals(expected, gate.assess(42L, plan, input));
        if (plan == AgentPlan.FREE) properties.setPaidModel("paid-b");
        else properties.setRelevanceFreeModel("relevance-free-b");
        assertEquals(expected, gate.assess(42L, plan, input));
        verify(client, times(1)).topicRelevance(any());

        if (plan == AgentPlan.FREE) properties.setRelevanceFreeModel("relevance-free-b");
        else properties.setPaidModel("paid-b");
        assertEquals(expected, gate.assess(42L, plan, input));
        verify(client, times(2)).topicRelevance(any());
        assertEquals(2, persistedHashes.size());
        assertNotEquals(persistedHashes.getFirst(), persistedHashes.getLast());
    }

    @Test void rejectsV8ResponseAfterV9Upgrade() {
        var request = new AgentTopicRelevanceRequest("test", AgentPlan.FREE,
                new AgentTopicRelevanceRequest.TopicInput(7L, "장비", "반도체 장비", List.of(), List.of(), List.of()),
                List.of(new AgentTopicRelevanceRequest.ArticleInput(1L, "공정위", null, "토스 분쟁")));
        var oldResponse = new AgentTopicRelevanceResponse(List.of(
                new AgentTopicRelevanceResponse.Decision(1L, "IRRELEVANT", "금융 분쟁", List.of("토스 분쟁"))),
                new AgentTopicRelevanceResponse.Meta("openai", "test-model", "topic-relevance.ko.v8",
                        10L, 5L, BigDecimal.ZERO, BigDecimal.ZERO, false, false));

        AgentClientException error = assertThrows(AgentClientException.class,
                () -> TopicRelevanceGate.validate(oldResponse, request));
        assertEquals("SCHEMA_VIOLATION", error.getCode());
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

    @Test void denseTopicAndFullTextStayWithinThePerArticleInputLimit() {
        reserve();
        var keywords = java.util.stream.IntStream.range(0, 100).mapToObj(i -> ("k" + i).repeat(100).substring(0, 100)).toList();
        Topic topic = Topic.builder().id(7L).name("장비").queryText("반도체 장비")
                .requiredKeywords(keywords).optionalKeywords(keywords).excludedKeywords(keywords).build();
        var candidates = LongStream.rangeClosed(1, 10).mapToObj(id -> new TopicRelevanceGate.Candidate(
                Article.builder().id(id).title("제목".repeat(500)).summary("요약".repeat(500))
                        .storedBody(ArticleBody.of("본문".repeat(2500))).fetchStatus(FetchStatus.FULLTEXT).topic(topic).build(), topic)).toList();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            assertEquals(1, request.articles().size());
            assertTrue(new ObjectMapper().writeValueAsString(request).length() <= 85_000);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), "UNCERTAIN", "판단 보류", List.of())).toList());
        });
        assertTrue(gate.assess(42L, AgentPlan.FREE, candidates).isEmpty());
        verify(client, times(10)).topicRelevance(any());
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

    @Test void warningWriteFailureDoesNotResettleOtherArticles() {
        reserve();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            var article = request.articles().getFirst();
            return response(List.of(new AgentTopicRelevanceResponse.Decision(article.articleId(),
                    article.articleId() == 1L ? "RELEVANT" : "UNCERTAIN", "주제 맥락 판정", List.of(article.title()))));
        });
        doThrow(new IllegalStateException("warning storage failed")).when(writer)
                .addAgentWarning(eq(42L), eq("TOPIC_RELEVANCE_UNCERTAIN"), anyString());

        assertEquals(Set.of(new TopicRelevanceGate.Key(1L, 7L)),
                gate.assess(42L, AgentPlan.FREE, List.of(candidate(1L, 7L), candidate(2L, 7L))));
        verify(finalizer, times(2)).success(eq(42L), any(), any(), anyList(), anyString(), any(), any());
        verify(finalizer, never()).failure(any(), any(), anyList(), anyString(), any(), any(), any());
    }

    @Test void angleBracketExpansionIsIncludedInEachArticleLimit() {
        reserve();
        var candidates = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> candidate(id, 7L, "<>".repeat(2500))).toList();
        when(client.topicRelevance(any())).thenAnswer(invocation -> {
            AgentTopicRelevanceRequest request = invocation.getArgument(0);
            assertEquals(1, request.articles().size());
            String serialized = new ObjectMapper().writeValueAsString(request);
            long expandedLength = serialized.length()
                    + 5L * serialized.chars().filter(c -> c == '<' || c == '>').count();
            assertTrue(expandedLength <= 85_000);
            return response(request.articles().stream().map(a -> new AgentTopicRelevanceResponse.Decision(
                    a.articleId(), "UNCERTAIN", "판단 보류", List.of())).toList());
        });

        assertTrue(gate.assess(42L, AgentPlan.FREE, candidates).isEmpty());
        verify(client, times(10)).topicRelevance(any());
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
