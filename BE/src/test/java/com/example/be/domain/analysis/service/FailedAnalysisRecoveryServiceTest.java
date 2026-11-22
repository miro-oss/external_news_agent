package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.quota.DuplicateQuotaReservationException;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailedAnalysisRecoveryServiceTest {
    private final FailedAnalysisRecoverySnapshot snapshots = mock(FailedAnalysisRecoverySnapshot.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final AgentRunRepository attempts = mock(AgentRunRepository.class);
    private final ArticleAnalysisOrchestrator orchestrator = mock(ArticleAnalysisOrchestrator.class);
    private final FindingReuseCache cache = mock(FindingReuseCache.class);
    private final FindingWriter writer = mock(FindingWriter.class);
    private final FailedAnalysisRecoveryService service = new FailedAnalysisRecoveryService(
            snapshots, findings, attempts, orchestrator, cache, writer);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../retry", "nov:23", "with space", "-leading", "한글"})
    void rejectsUnsafeRecoveryIdsBeforeReadingOrCallingProviders(String recoveryId) {
        assertThrows(IllegalArgumentException.class, () -> service.recover(42, recoveryId, 15));
        verifyNoInteractions(snapshots, findings, attempts, orchestrator, cache, writer);
    }

    @Test
    void skipsSuccessThatAppearedAfterPlanningAndReusesACompatibleCachedFinding() {
        load(10, 11);
        when(findings.findByRunIdAndArticleId(42L, 10L))
                .thenReturn(Optional.of(Finding.builder().analysisSource(AnalysisSource.REUSED).build()));
        when(cache.lookupContexts(anyList(), eq(AgentPlan.FREE))).thenReturn(Map.of(11L,
                new FindingReuseCache.Lookup("unused", Optional.of(result(AnalysisSource.REUSED)))));
        when(writer.recover(eq(42L), eq(11L), eq(ChangeType.NEW), anyString(), any())).thenReturn(true);

        var outcome = service.recover(42, "nov23", 15);

        assertEquals(List.of(10L), outcome.skippedArticleIds());
        assertEquals(List.of(11L), outcome.recoveredArticleIds());
        assertTrue(outcome.failedArticleIds().isEmpty());
        verifyNoInteractions(orchestrator);
        verify(writer, never()).recover(eq(42L), eq(10L), any(), anyString(), any());
    }

    @Test
    void firstProviderFallbackStopsTheBatchWithoutReplacingAnyFinding() {
        load(10, 11);
        when(orchestrator.analyze(any())).thenReturn(result(AnalysisSource.STUB));

        var outcome = service.recover(42, "nov23", 15);

        assertEquals(List.of(10L), outcome.failedArticleIds());
        assertTrue(outcome.recoveredArticleIds().isEmpty());
        verify(orchestrator, times(1)).analyze(argThat(context -> context.recovering()
                && context.recoveryId().equals("nov23") && !context.issue().present()));
        verifyNoInteractions(writer);
        verify(findings, never()).findByRunIdAndArticleId(42L, 11L);
    }

    @Test
    void priorRecoveryAuditStopsInsteadOfChargingAgain() {
        load(10, 11);
        when(attempts.findByIdempotencyKey("recovery:nov23:run:42:article:10"))
                .thenReturn(Optional.of(AgentRun.builder().build()));

        var outcome = service.recover(42, "nov23", 15);

        assertEquals(List.of(10L), outcome.skippedArticleIds());
        assertEquals(List.of(10L), outcome.failedArticleIds());
        verifyNoInteractions(cache, orchestrator, writer);
    }

    @Test
    void missingAuditButExistingReservationIsAlsoTerminalForTheSameRecoveryId() {
        load(10, 11);
        when(orchestrator.analyze(any())).thenThrow(new DuplicateQuotaReservationException("existing", "CONSUMED"));

        var outcome = service.recover(42, "nov23", 15);

        assertEquals(List.of(10L), outcome.skippedArticleIds());
        assertEquals(List.of(10L), outcome.failedArticleIds());
        verify(orchestrator, times(1)).analyze(any());
        verifyNoInteractions(writer);
    }

    @Test
    void successfulRecoveryWritesOriginalChangeTypeAndValidatedInputHash() {
        load(10);
        when(orchestrator.analyze(any())).thenReturn(result(AnalysisSource.LLM));
        when(writer.recover(eq(42L), eq(10L), eq(ChangeType.NEW), anyString(), any())).thenReturn(true);

        var outcome = service.recover(42, "nov23", 15);

        assertEquals(List.of(10L), outcome.recoveredArticleIds());
        verify(writer).recover(eq(42L), eq(10L), eq(ChangeType.NEW),
                eq(FindingReuseCache.inputHash(candidate(10).context())), any());
    }

    private void load(long... ids) {
        when(snapshots.load(42, 15)).thenReturn(new FailedAnalysisRecoverySnapshot.Snapshot(42,
                java.util.Arrays.stream(ids).mapToObj(this::candidate).toList(), 3, 0));
    }

    private FailedAnalysisRecoverySnapshot.Candidate candidate(long id) {
        var article = Article.builder().id(id).title("저장된 기사").body("검증된 근거 문장.")
                .canonicalUrl("https://example.com/" + id).fetchStatus(FetchStatus.FULLTEXT).build();
        return new FailedAnalysisRecoverySnapshot.Candidate(new AnalysisContext(42L, article, AgentPlan.FREE), ChangeType.NEW);
    }

    private AnalysisResult result(AnalysisSource source) {
        return new AnalysisResult("검증된 요약", List.of(new FindingKeyPoint("근거", List.of(0), "grounded")),
                null, Sentiment.NEUTRAL, FindingSensitivity.legacy(SensitivityLevel.LOW), Relevance.REFERENCE,
                "기술", List.of(), source, List.of(), null, List.of(), null);
    }
}
