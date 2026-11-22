package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.feedback.service.FeedbackLearningService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailedAnalysisRecoverySnapshotTest {
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final CollectionRunItemRepository items = mock(CollectionRunItemRepository.class);
    private final CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final AgentRunRepository attempts = mock(AgentRunRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackLearningService feedback = mock(FeedbackLearningService.class);
    private final FailedAnalysisRecoverySnapshot snapshots = new FailedAnalysisRecoverySnapshot(
            runs, items, observations, findings, attempts, relevance, feedback);
    private final LocalDateTime startedAt = LocalDateTime.of(2026, 11, 23, 8, 0);
    private final Source source = Source.builder().id(3L).name("저장 매체").build();
    private final Topic topic = Topic.builder().id(7L).name("기존 주제").queryText("기존 조건")
            .requiredKeywords(List.of("기존")).build();

    @BeforeEach
    void savedRun() {
        when(runs.findById(42L)).thenReturn(Optional.of(CollectionRun.builder().id(42L)
                .status(RunStatus.PARTIAL).startedAt(startedAt).llmPlan(AgentPlan.FREE).build()));
        when(items.findExecutionItemsByRunId(42L)).thenReturn(List.of(item(topic)));
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PENDING", "RUNNING"})
    void refusesInProgressRunsBeforeSelectingCandidates(RunStatus status) {
        when(runs.findById(42L)).thenReturn(Optional.of(CollectionRun.builder().status(status).startedAt(startedAt).build()));
        assertThrows(IllegalStateException.class, () -> snapshots.load(42, 15));
        verifyNoInteractions(items, observations, findings, attempts, relevance, feedback);
    }

    @Test
    void onlySelectsFailedStubFromExplicitObservedRunAndCapturedTopic() {
        Article failed = article(10, topic), success = article(11, topic), unobserved = article(12, topic);
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(stub(failed, topic),
                Finding.builder().article(success).analysisSource(AnalysisSource.LLM).build(), stub(unobserved, topic)));
        when(attempts.findFailedAnalysisArticleIds(42L)).thenReturn(List.of(10L, 11L, 12L, 13L));
        when(observations.findReportCoverageByRunIds(List.of(42L))).thenReturn(List.of(observed(failed, topic), observed(success, topic)));

        var snapshot = snapshots.load(42, 15);

        assertEquals(List.of(10L), snapshot.candidates().stream().map(c -> c.context().article().getId()).toList());
        assertEquals(1, snapshot.skippedSuccessful());
        assertEquals(2, snapshot.skippedUnavailable());
        assertNotSame(topic, snapshot.candidates().getFirst().context().topic());
        assertEquals("기존 조건", snapshot.candidates().getFirst().context().topic().getQueryText());
        verify(feedback).forTopic(any(), eq(startedAt), anySet());
    }

    @Test
    void selectsMatchingSavedTopicInsteadOfTheFirstSharedArticleObservation() {
        var other = Topic.builder().id(8L).name("두 번째 주제").queryText("다른 조건").build();
        var article = article(10, topic);
        when(items.findExecutionItemsByRunId(42L)).thenReturn(List.of(item(topic), item(other)));
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(stub(article, other)));
        when(attempts.findFailedAnalysisArticleIds(42L)).thenReturn(List.of(10L));
        when(observations.findReportCoverageByRunIds(List.of(42L)))
                .thenReturn(List.of(observed(article, topic), observed(article, other)));

        var snapshot = snapshots.load(42, 15);

        assertEquals(8L, snapshot.candidates().getFirst().context().topic().getId());
    }

    @Test
    void ignoresCurrentTopicEditsAndRefusesMissingOriginalTopicSnapshots() {
        var edited = Topic.builder().id(7L).name("나중에 편집한 이름").queryText("새 검색 조건").build();
        var article = article(10, edited);
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(stub(article, topic)));
        when(attempts.findFailedAnalysisArticleIds(42L)).thenReturn(List.of(10L));
        when(observations.findReportCoverageByRunIds(List.of(42L))).thenReturn(List.of(observed(article, edited)));

        assertEquals("기존 조건", snapshots.load(42, 15).candidates().getFirst().context().topic().getQueryText());
        when(items.findExecutionItemsByRunId(42L)).thenReturn(List.of(
                CollectionRunItem.builder().topic(edited).source(source).build()));
        assertEquals(1, snapshots.load(42, 15).skippedUnavailable());
    }

    @Test
    void excludesChangedOriginalInputAndRejectedTopicWithoutNewAssessment() {
        var article = article(10, topic);
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(Finding.builder().article(article)
                .analysisSource(AnalysisSource.STUB).analysisInputHash("original-no-longer-matches").build()));
        when(attempts.findFailedAnalysisArticleIds(42L)).thenReturn(List.of(10L));
        when(observations.findReportCoverageByRunIds(List.of(42L))).thenReturn(List.of(observed(article, topic)));
        assertEquals(1, snapshots.load(42, 15).skippedUnavailable());
        when(relevance.excludedKeys(42L)).thenReturn(Set.of(new TopicRelevanceGate.Key(10L, 7L)));
        assertTrue(snapshots.load(42, 15).candidates().isEmpty());
    }

    @Test
    void rejectsAnExceededExplicitLimitRatherThanSilentlyRecoveringAPrefix() {
        var first = article(10, topic); var second = article(11, topic);
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(stub(first, topic), stub(second, topic)));
        when(attempts.findFailedAnalysisArticleIds(42L)).thenReturn(List.of(10L, 11L));
        when(observations.findReportCoverageByRunIds(List.of(42L))).thenReturn(List.of(observed(first, topic), observed(second, topic)));
        assertThrows(IllegalStateException.class, () -> snapshots.load(42, 1));
    }

    private CollectionRunItem item(Topic value) {
        return CollectionRunItem.builder().topic(value).source(source)
                .topicSnapshot(CollectionTopicSnapshot.capture(value)).build();
    }
    private Article article(long id, Topic value) {
        return Article.builder().id(id).topic(value).source(source).title("기존 기사").body("기존 근거 문장.")
                .canonicalUrl("https://example.com/" + id).fetchStatus(FetchStatus.FULLTEXT).build();
    }
    private Finding stub(Article article, Topic selected) {
        var context = new AnalysisContext(42L, article, AgentPlan.FREE, null, false, selected, List.of());
        return Finding.builder().article(article).analysisSource(AnalysisSource.STUB).changeType(ChangeType.NEW)
                .analysisInputHash(FindingReuseCache.inputHash(context)).build();
    }
    private CollectionRunArticle observed(Article article, Topic selected) {
        return CollectionRunArticle.builder().article(article).topic(selected).changeType(ChangeType.NEW).build();
    }
}
