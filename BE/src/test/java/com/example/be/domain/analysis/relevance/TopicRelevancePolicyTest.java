package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.be.domain.analysis.relevance.TopicRelevanceStatus.IRRELEVANT;
import static com.example.be.domain.analysis.relevance.TopicRelevanceStatus.RELEVANT;
import static com.example.be.domain.analysis.relevance.TopicRelevanceStatus.UNCERTAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TopicRelevancePolicyTest {
    private final LocalDate date = LocalDate.of(2026, 9, 17);
    private final TopicRelevanceStore store = mock(TopicRelevanceStore.class);
    private final TopicRelevancePolicy policy = new TopicRelevancePolicy(store);

    @Test
    void emptyInputDoesNotLoadAssessments() {
        assertTrue(policy.filterFindings(List.of()).isEmpty());
        assertTrue(policy.filterDailyFindings(date, List.of()).isEmpty());
        verifyNoInteractions(store);
    }

    @Test
    void ownRunKeepsLegacyAndRelevantButExcludesIrrelevantAndUncertainInInputOrder() {
        Finding legacy = finding(1, 10, 101, 1, 9);
        Finding relevant = finding(2, 10, 102, 1, 9);
        Finding irrelevant = finding(3, 10, 103, 1, 9);
        Finding uncertain = finding(4, 10, 104, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(
                assessment(relevant, 1, RELEVANT), assessment(irrelevant, 1, IRRELEVANT),
                assessment(uncertain, 1, UNCERTAIN)));
        List<Finding> input = List.of(uncertain, relevant, irrelevant, legacy);

        assertEquals(List.of(relevant, legacy), policy.filterFindings(input));
        assertEquals(4, input.size());
        verify(store, times(1)).findByRun(10L);
        verifyNoMoreInteractions(store);
    }

    @Test
    void topicLookupDistinguishesLegacyFromRejectedAndReturnsOnlyAcceptedContexts() {
        Finding legacy = finding(1, 10, 101, 1, 9);
        Finding rejected = finding(2, 10, 102, 1, 9);
        Finding shared = finding(3, 10, 103, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(assessment(rejected, 1, UNCERTAIN),
                assessment(shared, 1, IRRELEVANT), assessment(shared, 2, RELEVANT)));
        assertTrue(policy.relevantTopicIds(10L, 101L).isEmpty());
        assertEquals(java.util.Optional.of(java.util.Set.of()), policy.relevantTopicIds(10L, 102L));
        assertEquals(java.util.Optional.of(java.util.Set.of(2L)), policy.relevantTopicIds(10L, 103L));
        assertEquals(java.util.Map.of(2L, java.util.Set.of(), 3L, java.util.Set.of(2L)),
                policy.relevantTopicIdsByFinding(List.of(legacy, rejected, shared)));
    }

    @Test
    void anArticleAcceptedForOneTopicRemainsVisibleDespiteOtherTopicRejection() {
        Finding shared = finding(1, 10, 101, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(
                assessment(shared, 1, IRRELEVANT), assessment(shared, 2, RELEVANT)));

        assertEquals(List.of(shared), policy.filterFindings(List.of(shared)));
    }

    @Test
    void aPreviousRunApprovalCannotOverrideCurrentRunRejectionForTheSameArticle() {
        Finding previous = finding(1, 10, 101, 1, 9);
        Finding current = finding(2, 11, 101, 1, 10);
        when(store.findByRun(10L)).thenReturn(List.of(assessment(previous, 1, RELEVANT)));
        when(store.findByRun(11L)).thenReturn(List.of(assessment(current, 1, IRRELEVANT)));

        assertEquals(List.of(previous), policy.filterFindings(List.of(current, previous)));
    }

    @Test
    void latestNegativeAssessmentSuppressesEarlierFindingEvenWithoutANewFinding() {
        Finding previous = finding(1, 10, 101, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(assessment(previous, 1, RELEVANT)));
        for (TopicRelevanceStatus latestStatus : List.of(IRRELEVANT, UNCERTAIN)) {
            when(store.latestOnDate(date, List.of(101L)))
                    .thenReturn(List.of(daily(101, 1, 11, 10, latestStatus)));
            assertTrue(policy.filterDailyFindings(date, List.of(previous)).isEmpty());
        }
        // Temporal suppression belongs only to a newly selected DAILY report, not historical RUN reads.
        assertEquals(List.of(previous), policy.filterFindings(List.of(previous)));
    }

    @Test
    void laterNegativeForAnotherTopicDoesNotSuppressAcceptedContext() {
        Finding accepted = finding(1, 10, 101, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(assessment(accepted, 1, RELEVANT)));
        when(store.latestOnDate(date, List.of(101L)))
                .thenReturn(List.of(daily(101, 2, 11, 10, IRRELEVANT)));

        assertEquals(List.of(accepted), policy.filterDailyFindings(date, List.of(accepted)));
    }

    @Test
    void sharedArticleIsSuppressedOnlyWhenAllItsAcceptedTopicsBecomeNonRelevant() {
        Finding shared = finding(1, 10, 101, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(
                assessment(shared, 1, RELEVANT), assessment(shared, 2, RELEVANT)));
        when(store.latestOnDate(date, List.of(101L))).thenReturn(List.of(
                daily(101, 1, 11, 10, IRRELEVANT), daily(101, 2, 11, 10, RELEVANT)));
        assertEquals(List.of(shared), policy.filterDailyFindings(date, List.of(shared)));

        when(store.latestOnDate(date, List.of(101L))).thenReturn(List.of(
                daily(101, 1, 11, 10, IRRELEVANT), daily(101, 2, 11, 10, UNCERTAIN)));
        assertTrue(policy.filterDailyFindings(date, List.of(shared)).isEmpty());
    }

    @Test
    void legacyFindingUsesItsArticleTopicAndIgnoresOlderNegativeDecisions() {
        Finding legacy = finding(1, 10, 101, 1, 9);
        when(store.latestOnDate(date, List.of(101L))).thenReturn(List.of(
                daily(101, 1, 9, 8, IRRELEVANT), daily(101, 2, 11, 10, IRRELEVANT)));
        assertEquals(List.of(legacy), policy.filterDailyFindings(date, List.of(legacy)));

        when(store.latestOnDate(date, List.of(101L)))
                .thenReturn(List.of(daily(101, 1, 11, 10, IRRELEVANT)));
        assertTrue(policy.filterDailyFindings(date, List.of(legacy)).isEmpty());
    }

    @Test
    void laterPositiveCannotReuseAFindingRejectedInItsOwnRun() {
        Finding rejected = finding(1, 10, 101, 1, 9);
        when(store.findByRun(10L)).thenReturn(List.of(assessment(rejected, 1, IRRELEVANT)));
        when(store.latestOnDate(date, List.of(101L)))
                .thenReturn(List.of(daily(101, 1, 11, 10, RELEVANT)));

        assertTrue(policy.filterDailyFindings(date, List.of(rejected)).isEmpty());
    }

    @Test
    void sameTimestampUsesRunIdSoAnOlderRejectionDoesNotHideANewerLegacyFinding() {
        Finding latest = finding(1, 11, 101, 1, 9);
        when(store.latestOnDate(date, List.of(101L)))
                .thenReturn(List.of(daily(101, 1, 10, 9, IRRELEVANT)));

        assertEquals(List.of(latest), policy.filterDailyFindings(date, List.of(latest)));

        when(store.latestOnDate(date, List.of(101L)))
                .thenReturn(List.of(daily(101, 1, 12, 9, IRRELEVANT)));
        assertTrue(policy.filterDailyFindings(date, List.of(latest)).isEmpty());
    }

    private Finding finding(long id, long runId, long articleId, long topicId, int hour) {
        return Finding.builder().id(id)
                .run(CollectionRun.builder().id(runId).startedAt(date.atTime(hour, 0)).build())
                .article(Article.builder().id(articleId).topic(Topic.builder().id(topicId).build()).build())
                .build();
    }

    private TopicRelevanceStore.Assessment assessment(Finding finding, long topicId, TopicRelevanceStatus status) {
        return new TopicRelevanceStore.Assessment(finding.getRun().getId(), topicId, finding.getArticle().getId(),
                status, "합성 판정", "a".repeat(64), "test.v1", "mock", "[]");
    }

    private TopicRelevanceStore.DailyAssessment daily(long articleId, long topicId, long runId, int hour,
                                                     TopicRelevanceStatus status) {
        return new TopicRelevanceStore.DailyAssessment(articleId, topicId, runId, date.atTime(hour, 0), status);
    }
}
