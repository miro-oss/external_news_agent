package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.AnalysisContext;
import com.example.be.domain.analysis.service.IssueAnalysisContext;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.analysis.service.FindingWriter;
import com.example.be.domain.issues.service.IssueCrossSourceWriter;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentAnalysisOrchestratorTest {

    private final AgentProperties properties = enabledProperties();
    private final AgentClient client = mock(AgentClient.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final StubArticleAnalyzer stub = mock(StubArticleAnalyzer.class);
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final LlmPlanService planService = mock(LlmPlanService.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final IssueCrossSourceWriter crossSourceWriter = mock(IssueCrossSourceWriter.class);
    private final FindingWriter findingWriter = mock(FindingWriter.class);
    private final QuotaReservation reservation = new QuotaReservation(
            1L, 42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.FREE, BigDecimal.ONE);
    private final QuotaReservation evidenceReservation = new QuotaReservation(
            2L, 42L, "run:42:article:10:evidence",
            AgentTask.VERIFY_EVIDENCE, AgentPlan.FREE, BigDecimal.ONE);
    private final AgentAnalysisOrchestrator orchestrator =
            new AgentAnalysisOrchestrator(
                    properties, client, recorder, stub, quotaService, planService, resultWriter,
                    crossSourceWriter, findingWriter);

    @BeforeEach
    void reserveQuota() {
        when(quotaService.reserve(42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.FREE))
                .thenReturn(reservation);
        when(quotaService.reserve(
                eq(42L),
                eq("run:42:article:10:evidence"),
                eq(AgentTask.VERIFY_EVIDENCE),
                eq(AgentPlan.FREE)))
                .thenReturn(evidenceReservation);
        when(client.verifyEvidence(any())).thenReturn(evidenceResponse("grounded", List.of(1)));
    }

    @Test
    void mapsOneBasedAgentEvidenceToZeroBasedPublicContractAndRecordsMockRun() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("한국어 요약", result.summary());
        assertEquals(List.of(0), result.keyPoints().getFirst().evidence());
        assertEquals(0, result.sections().getFirst().index());
        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertEquals(Relevance.REFERENCE, result.relevance());
        assertEquals(AnalysisSource.STUB, result.analysisSource());
        assertEquals(BigDecimal.ONE,
                result.analysisSections().getFirst().bullets().getFirst().confidence());
        assertEquals(List.of("HBM4"), result.entities().products());
        assertEquals(Audience.CHIP_MAKER, result.perspectiveTags().getFirst().audience());
        assertEquals(List.of(0), result.perspectiveTags().getFirst().evidenceSentenceIds());
        verify(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void comparesIssueMembersAndPromotesAtMostOneConflictArticle() {
        Article representative = article();
        Article member = Article.builder()
                .id(11L)
                .topic(representative.getTopic())
                .canonicalUrl("https://example.com/11")
                .title("삼성은 HBM4 양산이 지연된다고 발표")
                .summary("양산 지연")
                .body("삼성은 HBM4 양산이 지연된다고 발표했다.")
                .sourceName("충돌 출처")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        AnalysisContext context = new AnalysisContext(
                42L,
                representative,
                AgentPlan.FREE,
                new IssueAnalysisContext(88L, 10L, List.of(representative, member)));
        IssueCrossSource crossSource = new IssueCrossSource(
                List.of(),
                List.of(),
                List.of(new IssueCrossSource.Conflict(
                        List.of(10L, 11L), "양산 일정에 대한 보도가 충돌합니다.")),
                List.of("공급망 고객"));
        AgentAnalyzeResponse primary = comparisonResponse(
                response(List.of(1), "제품/공정", false),
                crossSource,
                List.of(11L),
                List.of(new AgentAnalyzeResponse.MemberStance(
                        11L, "DISPUTES", new BigDecimal("0.85"))));
        AgentAnalyzeResponse promoted = comparisonResponse(
                response(List.of(1), "제품/공정", false),
                IssueCrossSource.empty(),
                List.of(),
                List.of());
        QuotaReservation promotionReservation = new QuotaReservation(
                3L, 42L, "run:42:issue:88:promotion:article:11",
                AgentTask.ANALYZE, AgentPlan.FREE, BigDecimal.ONE);
        QuotaReservation promotionEvidenceReservation = new QuotaReservation(
                4L, 42L, "run:42:article:11:promotion:evidence",
                AgentTask.VERIFY_EVIDENCE, AgentPlan.FREE, BigDecimal.ONE);
        when(quotaService.reserve(
                42L,
                "run:42:issue:88:promotion:article:11",
                AgentTask.ANALYZE,
                AgentPlan.FREE)).thenReturn(promotionReservation);
        when(quotaService.reserve(
                42L,
                "run:42:article:11:promotion:evidence",
                AgentTask.VERIFY_EVIDENCE,
                AgentPlan.FREE)).thenReturn(promotionEvidenceReservation);
        when(client.analyze(any())).thenReturn(primary, promoted);

        orchestrator.analyze(context);

        ArgumentCaptor<AgentAnalyzeRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentAnalyzeRequest.class);
        verify(client, times(2)).analyze(requestCaptor.capture());
        assertEquals(List.of(11L), requestCaptor.getAllValues().get(0).issueMembers().stream()
                .map(AgentAnalyzeRequest.IssueMemberPayload::id)
                .toList());
        assertEquals(11L, requestCaptor.getAllValues().get(1).article().id());
        assertTrue(requestCaptor.getAllValues().get(1).issueMembers().isEmpty());
        verify(crossSourceWriter).applyRepresentative(
                eq(88L),
                eq(crossSource),
                eq(List.of(new IssueCrossSourceWriter.RuleStance(
                        11L, IssueStance.DISPUTES, new BigDecimal("0.85")))),
                eq(true));
        verify(findingWriter).write(eq(42L), eq(11L), eq(com.example.be.domain.collection.entity.ChangeType.UPDATED),
                any(), any());
        verify(crossSourceWriter).confirmPromotion(
                88L, 11L, IssueStance.DISPUTES, new BigDecimal("0.85"), true);
    }

    @Test
    void keepsRepresentativeResultWhenPromotionSetupFails() {
        Article representative = article();
        Article member = issueMember(representative, 11L, "충돌 기사", "양산 일정 5조원");
        AnalysisContext context = new AnalysisContext(
                42L,
                representative,
                AgentPlan.FREE,
                new IssueAnalysisContext(88L, 10L, List.of(representative, member)));
        AgentAnalyzeResponse primary = comparisonResponse(
                response(List.of(1)),
                new IssueCrossSource(
                        List.of(),
                        List.of(),
                        List.of(new IssueCrossSource.Conflict(
                                List.of(10L, 11L), "투자 규모가 다릅니다.")),
                        List.of()),
                List.of(11L),
                List.of(new AgentAnalyzeResponse.MemberStance(
                        11L, "DISPUTES", new BigDecimal("0.85"))));
        when(client.analyze(any())).thenReturn(primary);
        when(quotaService.reserve(
                42L,
                "run:42:issue:88:promotion:article:11",
                AgentTask.ANALYZE,
                AgentPlan.FREE)).thenThrow(new IllegalStateException("quota store unavailable"));

        AnalysisResult result = orchestrator.analyze(context);

        assertEquals("한국어 요약", result.summary());
        verify(resultWriter).addAgentWarning(
                42L,
                com.example.be.domain.collection.entity.CollectionRunWarning.CODE_LLM_CROSS_SOURCE_FAILED,
                "충돌 기사 승격 처리에 실패했습니다.");
        verifyNoInteractions(findingWriter);
    }

    @Test
    void skipsPromotionWhenCandidateIsAlreadyAPrimaryPipelineTarget() {
        Article representative = article();
        Article member = issueMember(representative, 11L, "다른 이슈 대표", "양산 일정 5조원");
        AnalysisContext context = new AnalysisContext(
                42L,
                representative,
                AgentPlan.FREE,
                new IssueAnalysisContext(
                        88L, 10L, List.of(representative, member), Set.of(10L, 11L)));
        AgentAnalyzeResponse primary = comparisonResponse(
                response(List.of(1)),
                new IssueCrossSource(
                        List.of(),
                        List.of(),
                        List.of(new IssueCrossSource.Conflict(
                                List.of(10L, 11L), "투자 규모가 다릅니다.")),
                        List.of()),
                List.of(11L),
                List.of(new AgentAnalyzeResponse.MemberStance(
                        11L, "DISPUTES", new BigDecimal("0.85"))));
        when(client.analyze(any())).thenReturn(primary);

        orchestrator.analyze(context);

        verify(client).analyze(any());
        verifyNoInteractions(findingWriter);
    }

    @Test
    void capsIssueMembersAndTruncatesSummariesToAgentContract() {
        Article base = article();
        Article representative = Article.builder()
                .id(base.getId())
                .topic(base.getTopic())
                .canonicalUrl(base.getCanonicalUrl())
                .title(base.getTitle())
                .summary("가".repeat(2100))
                .body(base.getBody())
                .language(base.getLanguage())
                .fetchStatus(base.getFetchStatus())
                .build();
        List<Article> members = Stream.iterate(11L, id -> id + 1)
                .limit(12)
                .map(id -> issueMember(
                        representative, id, "멤버 " + id, "나".repeat(2100)))
                .toList();
        List<AgentAnalyzeResponse.MemberStance> stances = members.stream()
                .limit(10)
                .map(member -> new AgentAnalyzeResponse.MemberStance(
                        member.getId(), "SUPPORTS", new BigDecimal("0.55")))
                .toList();
        AnalysisContext context = new AnalysisContext(
                42L,
                representative,
                AgentPlan.FREE,
                new IssueAnalysisContext(
                        88L,
                        10L,
                        Stream.concat(Stream.of(representative), members.stream()).toList()));
        when(client.analyze(any())).thenReturn(comparisonResponse(
                response(List.of(1)), IssueCrossSource.empty(), List.of(), stances));

        orchestrator.analyze(context);

        ArgumentCaptor<AgentAnalyzeRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentAnalyzeRequest.class);
        verify(client).analyze(requestCaptor.capture());
        assertEquals(10, requestCaptor.getValue().issueMembers().size());
        assertEquals(2000, requestCaptor.getValue().article().summary().length());
        assertEquals(2000, requestCaptor.getValue().issueMembers().getFirst().summary().length());
    }

    @Test
    void rejectsIncompletePerspectiveTagsAndFallsBackToStub() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        AgentAnalyzeResponse valid = response(List.of(1));
        AgentAnalyzeResponse invalid = new AgentAnalyzeResponse(
                valid.sentences(),
                valid.sections(),
                valid.summaryKo(),
                valid.classification(),
                valid.entities(),
                valid.perspectiveTags().subList(0, 3),
                valid.meta());
        when(client.analyze(any())).thenReturn(invalid);
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(), any());
    }

    @Test
    void keepsAnalysisAndDiscardsPerspectiveTagsWhenMoreThanTwoAreHigh() {
        AgentAnalyzeResponse valid = response(List.of(1));
        List<AgentAnalyzeResponse.PerspectiveTag> invalidTags = valid.perspectiveTags().stream()
                .map(tag -> new AgentAnalyzeResponse.PerspectiveTag(
                        tag.audience(), "high", "핵심 주장", List.of(1)))
                .toList();
        AgentAnalyzeResponse invalid = new AgentAnalyzeResponse(
                valid.sentences(),
                valid.sections(),
                valid.summaryKo(),
                valid.classification(),
                valid.entities(),
                invalidTags,
                valid.meta());
        when(client.analyze(any())).thenReturn(invalid);

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("한국어 요약", result.summary());
        assertTrue(result.perspectiveTags().isEmpty());
        verify(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any(LocalDateTime.class));
        verifyNoInteractions(stub);
    }

    @Test
    void marksRealProviderAnalysisAsLlmAndKeepsMetadata() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals(AnalysisSource.LLM, result.analysisSource());
        assertEquals("gemini", result.metadata().provider());
        assertEquals("gemini-2.5-flash", result.metadata().model());
        assertEquals(
                "analyze.ko.v5+perspective.ko.v1+sensitivity.ko.v1",
                result.metadata().promptVersion());
        assertEquals(120L, result.metadata().inputTokens());
        assertEquals(30L, result.metadata().outputTokens());
        assertEquals(new BigDecimal("0.001"), result.metadata().costUsd());
        assertFalse(result.metadata().truncated());
        ArgumentCaptor<AgentEvidenceRequest> captor =
                ArgumentCaptor.forClass(AgentEvidenceRequest.class);
        verify(client).verifyEvidence(captor.capture());
        assertEquals(1, captor.getValue().claims().size());
        assertEquals("0:0", captor.getValue().claims().getFirst().claimId());
        assertEquals("핵심 주장", captor.getValue().claims().getFirst().claim());
        assertEquals("FACT", captor.getValue().claims().getFirst().claimType());
        assertEquals(
                "근거 문장.",
                captor.getValue().claims().getFirst().sentences().getFirst().text());
        verify(quotaService).completeSuccess(evidenceReservation, BigDecimal.ZERO, true);
    }

    @Test
    void settlesRuleOnlyEvidenceWithoutConsumingProviderQuota() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));
        when(client.verifyEvidence(any())).thenReturn(ruleEvidenceResponse());

        orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        verify(quotaService).completeSuccess(evidenceReservation, BigDecimal.ZERO, false);
    }

    @Test
    void verifiesMultipleBulletsWithOneBatchCallAndOneQuotaReservation() {
        AgentAnalyzeResponse base = response(List.of(1), "제품/공정", false);
        AgentAnalyzeResponse batched = new AgentAnalyzeResponse(
                base.sentences(),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(
                                new AgentAnalyzeResponse.Bullet(
                                        "첫 주장", List.of(1), "grounded", BigDecimal.ONE,
                                        "FACT", null),
                                new AgentAnalyzeResponse.Bullet(
                                        "두 번째 전망일 수 있다", List.of(1), "weak", new BigDecimal("0.7"),
                                        "FORECAST", null)))),
                base.summaryKo(),
                base.classification(),
                base.entities(),
                base.perspectiveTags(),
                base.meta());
        when(client.analyze(any())).thenReturn(batched);
        when(client.verifyEvidence(any())).thenReturn(new AgentEvidenceResponse(
                List.of(
                        new AgentEvidenceResponse.Result(
                                "0:0", "grounded", List.of(1), "직접 확인"),
                        new AgentEvidenceResponse.Result(
                                "0:1", "weak", List.of(1), "전망 표현으로 제한")),
                ruleEvidenceResponse().meta()));

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article(), AgentPlan.FREE));

        ArgumentCaptor<AgentEvidenceRequest> captor =
                ArgumentCaptor.forClass(AgentEvidenceRequest.class);
        verify(client).verifyEvidence(captor.capture());
        assertEquals(List.of("0:0", "0:1"), captor.getValue().claims().stream()
                .map(AgentEvidenceRequest.ClaimPayload::claimId)
                .toList());
        assertEquals("FORECAST", result.keyPoints().get(1).claimType());
        assertEquals("전망 표현으로 제한", result.keyPoints().get(1).groundingReason());
        verify(quotaService, times(1)).reserve(
                42L,
                "run:42:article:10:evidence",
                AgentTask.VERIFY_EVIDENCE,
                AgentPlan.FREE);
    }

    @Test
    void rejectsRuleOnlyMetaThatClaimsProviderUsage() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));
        AgentEvidenceResponse valid = ruleEvidenceResponse();
        when(client.verifyEvidence(any())).thenReturn(new AgentEvidenceResponse(
                valid.results(),
                new AgentEvidenceResponse.Meta(
                        valid.meta().provider(),
                        valid.meta().model(),
                        valid.meta().promptVersion(),
                        1L,
                        valid.meta().outputTokens(),
                        valid.meta().costUsd(),
                        valid.meta().credits(),
                        valid.meta().mock(),
                        valid.meta().truncated())));

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("ungrounded", result.keyPoints().getFirst().groundedness());
        verify(quotaService).completeFailure(
                eq(evidenceReservation), any(AgentClientException.class));
    }

    @Test
    void removesEvidenceWhenVerifierRejectsRealProviderBullet() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));
        when(client.verifyEvidence(any())).thenReturn(evidenceResponse("ungrounded", List.of()));

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("ungrounded", result.keyPoints().getFirst().groundedness());
        assertEquals(List.of(), result.keyPoints().getFirst().evidence());
        assertEquals(BigDecimal.ZERO,
                result.analysisSections().getFirst().bullets().getFirst().confidence());
    }

    @Test
    void usesFallbackReservationKeyAndFreePlanWhenPaidQuotaIsExhausted() {
        QuotaReservation fallback = new QuotaReservation(
                2L, 42L, "run:42:article:10:fallback-free",
                AgentTask.ANALYZE, AgentPlan.FREE, BigDecimal.ONE);
        when(quotaService.reserve(42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID))
                .thenThrow(new QuotaExceededException(AgentPlan.PAID, "exhausted"));
        when(planService.paidExhaustedAction()).thenReturn(PaidExhaustedAction.FALLBACK_FREE);
        when(quotaService.reserve(
                42L, "run:42:article:10:fallback-free", AgentTask.ANALYZE, AgentPlan.FREE))
                .thenReturn(fallback);
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));

        orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.PAID));

        ArgumentCaptor<AgentAnalyzeRequest> captor = ArgumentCaptor.forClass(AgentAnalyzeRequest.class);
        verify(client).analyze(captor.capture());
        assertEquals(AgentPlan.FREE, captor.getValue().plan());
        assertEquals("run:42:article:10:fallback-free", captor.getValue().idempotencyKey());
    }

    @Test
    void recordsFailureAndFallsBackToStubWhenEvidenceDoesNotExist() {
        AnalysisResult stubResult = mock(AnalysisResult.class);
        Article article = article();
        when(client.analyze(any())).thenReturn(response(List.of(2)));
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("EVIDENCE_MISSING"), any(), any(), any(),
                any(LocalDateTime.class));
    }

    @Test
    void returnsSuccessfulAnalysisEvenWhenAuditRecordingFails() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any());

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("한국어 요약", result.summary());
    }

    @Test
    void fallsBackEvenWhenFailureAuditRecordingFails() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenThrow(new IllegalStateException("invalid response"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordFailure(
                        eq(42L), eq(10L), any(), any(), any(), any(), any(), any());
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
    }

    @Test
    void rejectsUnknownCategoryAndFallsBackToStub() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenReturn(response(List.of(1), "반도체"));
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStructuredResponses")
    void rejectsInvalidStructuredFieldsAndFallsBackToStub(
            String caseName,
            AgentAnalyzeResponse invalidResponse
    ) {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenReturn(invalidResponse);
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result, caseName);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(), any());
    }

    private AgentProperties enabledProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setToken("test-agent-token");
        properties.setDefaultPlan(AgentPlan.FREE);
        return properties;
    }

    private Article article() {
        return Article.builder()
                .id(10L)
                .topic(Topic.builder()
                        .name("HBM")
                        .queryText("HBM")
                        .requiredKeywords(List.of("HBM"))
                        .optionalKeywords(List.of())
                        .excludedKeywords(List.of())
                        .build())
                .canonicalUrl("https://example.com/10")
                .title("기사 제목")
                .body("근거 문장.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
    }

    private Article issueMember(Article representative,
                                Long id,
                                String title,
                                String summary) {
        return Article.builder()
                .id(id)
                .topic(representative.getTopic())
                .canonicalUrl("https://example.com/" + id)
                .title(title)
                .summary(summary)
                .body(title + " 본문")
                .sourceName("다른경제")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
    }

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds) {
        return response(evidenceIds, "제품/공정");
    }

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds, String category) {
        return response(evidenceIds, category, true);
    }

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds,
                                                 String category,
                                                 boolean mock) {
        return new AgentAnalyzeResponse(
                List.of("근거 문장."),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(new AgentAnalyzeResponse.Bullet(
                                "핵심 주장", evidenceIds, "grounded", BigDecimal.ONE)))),
                "한국어 요약",
                new AgentAnalyzeResponse.Classification(
                        "산업 동향 보도", "neutral", "low", "reference", category),
                new AgentAnalyzeResponse.Entities(List.of("SK하이닉스"), List.of("HBM4"), List.of()),
                perspectiveTags(),
                new AgentAnalyzeResponse.Meta(
                        mock ? "mock" : "gemini",
                        mock ? "mock" : "gemini-2.5-flash",
                        mock
                                ? "analyze.mock.v5"
                                : "analyze.ko.v5+perspective.ko.v1+sensitivity.ko.v1",
                        mock ? 0L : 120L,
                        mock ? 0L : 30L,
                        mock ? BigDecimal.ZERO : new BigDecimal("0.001"),
                        BigDecimal.ZERO,
                        mock,
                        false));
    }

    private static AgentAnalyzeResponse comparisonResponse(
            AgentAnalyzeResponse source,
            IssueCrossSource crossSource,
            List<Long> promoteCandidates,
            List<AgentAnalyzeResponse.MemberStance> memberStances) {
        return new AgentAnalyzeResponse(
                source.sentences(),
                source.sections(),
                source.summaryKo(),
                source.classification(),
                source.entities(),
                source.perspectiveTags(),
                new AgentAnalyzeResponse.CrossSource(
                        crossSource.consensus(),
                        crossSource.soleSource().stream()
                                .map(value -> new AgentAnalyzeResponse.SoleSourceObservation(
                                        value.articleId(), value.text()))
                                .toList(),
                        crossSource.conflicts().stream()
                                .map(value -> new AgentAnalyzeResponse.ConflictObservation(
                                        value.articleIds(), value.text()))
                                .toList(),
                        crossSource.missingStakeholders()),
                promoteCandidates,
                memberStances,
                source.meta());
    }

    private static AgentEvidenceResponse evidenceResponse(String status, List<Integer> acceptedIds) {
        return new AgentEvidenceResponse(
                List.of(new AgentEvidenceResponse.Result(
                        "0:0", status, acceptedIds, "검증 결과")),
                new AgentEvidenceResponse.Meta(
                        "gemini",
                        "gemini-2.5-flash",
                        "evidence.ko.v2",
                        10L,
                        5L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        false));
    }

    private static AgentEvidenceResponse ruleEvidenceResponse() {
        return new AgentEvidenceResponse(
                List.of(new AgentEvidenceResponse.Result(
                        "0:0", "grounded", List.of(1), "규칙 검증 결과")),
                new AgentEvidenceResponse.Meta(
                        "gemini",
                        "evidence-rules-v3",
                        "evidence.rules.v3",
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        false));
    }

    private static Stream<Arguments> invalidStructuredResponses() {
        AgentAnalyzeResponse valid = response(List.of(1));
        AgentAnalyzeResponse.Section validSection = valid.sections().getFirst();
        AgentAnalyzeResponse.Bullet validBullet = validSection.bullets().getFirst();
        return Stream.of(
                Arguments.of("entities null", copy(valid, valid.sections(), null, valid.meta())),
                Arguments.of("negative token", copy(
                        valid,
                        valid.sections(),
                        valid.entities(),
                        new AgentAnalyzeResponse.Meta(
                                "mock", "mock", "analyze.mock.v1", -1L, 0L,
                                BigDecimal.ZERO, BigDecimal.ZERO, true, false))),
                Arguments.of("confidence out of range", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(
                                "핵심",
                                List.of(new AgentAnalyzeResponse.Bullet(
                                        validBullet.text(),
                                        validBullet.evidenceSentenceIds(),
                                        validBullet.groundedness(),
                                        new BigDecimal("1.1"))))),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("blank heading", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(" ", validSection.bullets())),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("unknown groundedness", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(
                                "핵심",
                                List.of(new AgentAnalyzeResponse.Bullet(
                                        validBullet.text(),
                                        validBullet.evidenceSentenceIds(),
                                        "unknown",
                                        validBullet.confidence())))),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("empty sections", copy(
                        valid, List.of(), valid.entities(), valid.meta()))
        );
    }

    private static AgentAnalyzeResponse copy(
            AgentAnalyzeResponse source,
            List<AgentAnalyzeResponse.Section> sections,
            AgentAnalyzeResponse.Entities entities,
            AgentAnalyzeResponse.Meta meta
    ) {
        return new AgentAnalyzeResponse(
                source.sentences(),
                sections,
                source.summaryKo(),
                source.classification(),
                entities,
                source.perspectiveTags(),
                meta);
    }

    private static List<AgentAnalyzeResponse.PerspectiveTag> perspectiveTags() {
        return List.of(
                new AgentAnalyzeResponse.PerspectiveTag(
                        Audience.CHIP_MAKER.name(), "high", "핵심 주장", List.of(1)),
                new AgentAnalyzeResponse.PerspectiveTag(
                        Audience.EQUIPMENT_MAKER.name(), "none", null, List.of()),
                new AgentAnalyzeResponse.PerspectiveTag(
                        Audience.MARKET_INVESTOR.name(), "none", null, List.of()),
                new AgentAnalyzeResponse.PerspectiveTag(
                        Audience.IT_INFRA.name(), "none", null, List.of()));
    }
}
