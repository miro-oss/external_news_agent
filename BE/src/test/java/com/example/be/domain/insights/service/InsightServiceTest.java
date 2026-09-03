package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.dto.InsightDTO;
import com.example.be.domain.insights.entity.InsightFact;
import com.example.be.domain.insights.entity.InsightImplication;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InsightServiceTest {

    private AgentProperties properties;
    private InsightInputAssembler inputAssembler;
    private InsightPersistenceService persistenceService;
    private AgentClient agentClient;
    private AgentQuotaService quotaService;
    private AgentRunRecorder runRecorder;
    private LlmPlanService planService;
    private InsightService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setInsightPromptVersion("insight.ko.v2+perspective.ko.v1");
        inputAssembler = mock(InsightInputAssembler.class);
        persistenceService = mock(InsightPersistenceService.class);
        agentClient = mock(AgentClient.class);
        quotaService = mock(AgentQuotaService.class);
        runRecorder = mock(AgentRunRecorder.class);
        planService = mock(LlmPlanService.class);
        service = new InsightService(
                properties,
                inputAssembler,
                persistenceService,
                agentClient,
                quotaService,
                runRecorder,
                planService);
    }

    @Test
    void returnsExactCacheWithoutAgentCallOrQuotaCharge() {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        NewsInsight cached = entity(Audience.CHIP_MAKER);
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(
                eq(AgentTargetType.ISSUE), eq(88L), eq(snapshot.inputHash()),
                eq(properties.getInsightPromptVersion()), anyCollection()))
                .thenReturn(List.of(cached));
        when(persistenceService.toDto(cached)).thenReturn(dto(Audience.CHIP_MAKER));

        InsightDTO.Result result = service.create(new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER")));

        assertTrue(result.cached());
        assertEquals(List.of(Audience.CHIP_MAKER),
                result.insights().stream().map(InsightDTO.AudienceInsight::audience).toList());
        verifyNoInteractions(agentClient, quotaService, runRecorder, planService);
    }

    @Test
    void generatesAllMissingAudiencesInOneCallAndSettlesQuota() {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        QuotaReservation reservation = new QuotaReservation(
                1L, 42L, "reservation", AgentTask.INSIGHT, AgentPlan.PAID, BigDecimal.ONE);
        AgentInsightResponse response = response();
        NewsInsight saved = entity(Audience.CHIP_MAKER);
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(
                any(), eq(88L), eq(snapshot.inputHash()), any(), anyCollection()))
                .thenReturn(List.of());
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.PAID, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(
                eq(42L), any(), eq(AgentTask.INSIGHT), eq(AgentPlan.PAID)))
                .thenReturn(reservation);
        when(agentClient.insight(any())).thenReturn(response);
        when(persistenceService.saveGenerated(
                AgentTargetType.ISSUE,
                88L,
                snapshot.inputHash(),
                response,
                snapshot.articleIdsByFinding()))
                .thenReturn(List.of(saved));
        when(persistenceService.toDto(saved)).thenReturn(dto(Audience.CHIP_MAKER));

        InsightDTO.Result result = service.create(new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER")));

        assertFalse(result.cached());
        ArgumentCaptor<AgentInsightRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentInsightRequest.class);
        verify(agentClient).insight(requestCaptor.capture());
        assertEquals(List.of("CHIP_MAKER"), requestCaptor.getValue().audiences());
        assertEquals(1, requestCaptor.getValue().findings().size());
        InOrder finalization = org.mockito.Mockito.inOrder(
                persistenceService, runRecorder, quotaService);
        finalization.verify(persistenceService).saveGenerated(
                AgentTargetType.ISSUE,
                88L,
                snapshot.inputHash(),
                response,
                snapshot.articleIdsByFinding());
        finalization.verify(runRecorder).recordInsightSuccess(
                eq(42L), eq(88L), any(), eq(response), any(LocalDateTime.class));
        finalization.verify(quotaService).completeSuccess(reservation, BigDecimal.ONE);
    }

    @Test
    void requestsOnlyMissingAudienceWhenOtherAudienceIsCached() {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        NewsInsight chip = entity(Audience.CHIP_MAKER);
        NewsInsight infra = entity(Audience.IT_INFRA);
        QuotaReservation reservation = new QuotaReservation(
                1L, 42L, "reservation", AgentTask.INSIGHT, AgentPlan.FREE, BigDecimal.ONE);
        AgentInsightResponse infraResponse = response(Audience.IT_INFRA);
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of(chip));
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.FREE, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(any(), any(), any(), any())).thenReturn(reservation);
        when(agentClient.insight(any())).thenReturn(infraResponse);
        when(persistenceService.saveGenerated(any(), any(), any(), any(), any()))
                .thenReturn(List.of(infra));
        when(persistenceService.toDto(chip)).thenReturn(dto(Audience.CHIP_MAKER));
        when(persistenceService.toDto(infra)).thenReturn(dto(Audience.IT_INFRA));

        InsightDTO.Result result = service.create(new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER", "IT_INFRA")));

        ArgumentCaptor<AgentInsightRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentInsightRequest.class);
        verify(agentClient).insight(requestCaptor.capture());
        assertEquals(List.of("IT_INFRA"), requestCaptor.getValue().audiences());
        assertEquals(List.of(Audience.CHIP_MAKER, Audience.IT_INFRA),
                result.insights().stream().map(InsightDTO.AudienceInsight::audience).toList());
        verify(agentClient, never()).analyze(any());
    }

    @Test
    void usesDistinctDeterministicQuotaKeysForDifferentMissingAudienceSets() {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        NewsInsight chip = entity(Audience.CHIP_MAKER);
        NewsInsight infra = entity(Audience.IT_INFRA);
        QuotaReservation chipReservation = new QuotaReservation(
                1L, 42L, "chip", AgentTask.INSIGHT, AgentPlan.FREE, BigDecimal.ONE);
        QuotaReservation infraReservation = new QuotaReservation(
                2L, 42L, "infra", AgentTask.INSIGHT, AgentPlan.FREE, BigDecimal.ONE);
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of(), List.of(chip));
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.FREE, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(any(), any(), any(), any()))
                .thenReturn(chipReservation, infraReservation);
        when(agentClient.insight(any()))
                .thenReturn(response(Audience.CHIP_MAKER), response(Audience.IT_INFRA));
        when(persistenceService.saveGenerated(any(), any(), any(), any(), any()))
                .thenReturn(List.of(chip), List.of(infra));
        when(persistenceService.toDto(chip)).thenReturn(dto(Audience.CHIP_MAKER));
        when(persistenceService.toDto(infra)).thenReturn(dto(Audience.IT_INFRA));

        service.create(new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER")));
        service.create(new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER", "IT_INFRA")));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(quotaService, times(2)).reserve(
                eq(42L), keyCaptor.capture(), eq(AgentTask.INSIGHT), eq(AgentPlan.FREE));
        List<String> keys = keyCaptor.getAllValues();
        assertNotEquals(keys.get(0), keys.get(1));
        assertTrue(keys.get(0).endsWith(":CHIP_MAKER"));
        assertTrue(keys.get(1).endsWith(":IT_INFRA"));
    }

    @Test
    void serializesIdenticalCacheMissesAndRechecksCacheAfterWinnerPersists() throws Exception {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        NewsInsight chip = entity(Audience.CHIP_MAKER);
        QuotaReservation reservation = new QuotaReservation(
                1L, 42L, "reservation", AgentTask.INSIGHT, AgentPlan.FREE, BigDecimal.ONE);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch allowProviderCompletion = new CountDownLatch(1);
        CountDownLatch inputAssembled = new CountDownLatch(2);
        AtomicBoolean persisted = new AtomicBoolean(false);
        when(inputAssembler.assemble(88L)).thenAnswer(ignored -> {
            inputAssembled.countDown();
            return snapshot;
        });
        when(persistenceService.findCached(any(), any(), any(), any(), anyCollection()))
                .thenAnswer(ignored -> persisted.get() ? List.of(chip) : List.of());
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.FREE, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(any(), any(), any(), any())).thenReturn(reservation);
        when(agentClient.insight(any())).thenAnswer(ignored -> {
            providerStarted.countDown();
            if (!allowProviderCompletion.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("provider completion timeout");
            }
            return response();
        });
        when(persistenceService.saveGenerated(any(), any(), any(), any(), any()))
                .thenAnswer(ignored -> {
                    persisted.set(true);
                    return List.of(chip);
                });
        when(persistenceService.toDto(chip)).thenReturn(dto(Audience.CHIP_MAKER));
        InsightDTO.CreateRequest request = new InsightDTO.CreateRequest(
                "ISSUE", 88L, List.of("CHIP_MAKER"));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<InsightDTO.Result> first = executor.submit(() -> service.create(request));
            assertTrue(providerStarted.await(2, TimeUnit.SECONDS));
            Future<InsightDTO.Result> second = executor.submit(() -> service.create(request));
            assertTrue(inputAssembled.await(2, TimeUnit.SECONDS));
            allowProviderCompletion.countDown();

            assertFalse(first.get(2, TimeUnit.SECONDS).cached());
            assertTrue(second.get(2, TimeUnit.SECONDS).cached());
        } finally {
            allowProviderCompletion.countDown();
            executor.shutdownNow();
        }

        verify(agentClient, times(1)).insight(any());
        verify(quotaService, times(1)).reserve(any(), any(), any(), any());
    }

    @Test
    void rejectsAudienceThatWasNotRequested() {
        assertRejected(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("CHIP_MAKER")),
                response(Audience.IT_INFRA));
    }

    @Test
    void rejectsUnknownFindingOrSentenceReference() {
        AgentInsightResponse valid = response();
        AgentInsightResponse.Insight insight = valid.insights().getFirst();
        AgentInsightResponse invalid = withInsight(valid, new AgentInsightResponse.Insight(
                insight.audience(),
                insight.headline(),
                List.of(new AgentInsightResponse.Fact(
                        "FACT", "f1", "HBM4 양산 일정이 앞당겨졌다.",
                        999L, List.of(7), "grounded", "원문에서 확인됩니다.")),
                insight.implications(),
                insight.watchNext(),
                insight.confidence()));

        assertRejected(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("CHIP_MAKER")),
                invalid);
    }

    @Test
    void rejectsImplicationThatReferencesUngroundedFact() {
        AgentInsightResponse valid = response();
        AgentInsightResponse.Insight insight = valid.insights().getFirst();
        AgentInsightResponse invalid = withInsight(valid, new AgentInsightResponse.Insight(
                insight.audience(),
                insight.headline(),
                List.of(new AgentInsightResponse.Fact(
                        "FACT", "f1", "HBM4 양산 일정이 앞당겨졌다.",
                        501L, List.of(1), "ungrounded", "근거가 부족합니다.")),
                insight.implications(),
                insight.watchNext(),
                insight.confidence()));

        assertRejected(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("CHIP_MAKER")),
                invalid);
    }

    @Test
    void rejectsInvestmentAdviceInMarketHeadlineFactsAndWatchItems() {
        AgentInsightResponse valid = response(Audience.MARKET_INVESTOR);
        AgentInsightResponse.Insight insight = valid.insights().getFirst();
        AgentInsightResponse invalid = withInsight(valid, new AgentInsightResponse.Insight(
                insight.audience(),
                "지금이 매수 시점",
                List.of(new AgentInsightResponse.Fact(
                        "FACT", "f1", "지금 매도해야 합니다.",
                        501L, List.of(1), "grounded", "원문에서 확인됩니다.")),
                insight.implications(),
                List.of("목표가 상향 여부"),
                insight.confidence()));

        assertRejected(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("MARKET_INVESTOR")),
                invalid);
    }

    @Test
    void rejectsTruncatedAgentResponse() {
        AgentInsightResponse valid = response();
        AgentInsightResponse.Meta meta = valid.meta();
        AgentInsightResponse truncated = new AgentInsightResponse(
                valid.insights(),
                new AgentInsightResponse.Meta(
                        meta.provider(), meta.model(), meta.promptVersion(),
                        meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits(),
                        meta.mock(), true));

        assertRejected(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("CHIP_MAKER")),
                truncated);
    }

    @Test
    void releasesQuotaAndRecordsFailureWhenPersistenceFails() {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        QuotaReservation reservation = new QuotaReservation(
                1L, 42L, "reservation", AgentTask.INSIGHT, AgentPlan.PAID, BigDecimal.ONE);
        AgentInsightResponse response = response();
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.PAID, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(any(), any(), any(), any())).thenReturn(reservation);
        when(agentClient.insight(any())).thenReturn(response);
        when(persistenceService.saveGenerated(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(GeneralException.class, () -> service.create(
                new InsightDTO.CreateRequest("ISSUE", 88L, List.of("CHIP_MAKER"))));

        verify(runRecorder).recordInsightFailure(
                eq(42L),
                eq(88L),
                any(),
                eq("PERSISTENCE_FAILED"),
                eq("인사이트 저장에 실패했습니다."),
                any(AgentClientException.Usage.class),
                eq(null),
                any(LocalDateTime.class));
        verify(quotaService).completeFailure(reservation, "SCHEMA_VIOLATION");
        verify(quotaService, never()).completeSuccess(any(), any());
        verify(runRecorder, never()).recordInsightSuccess(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsDisabledFeatureBeforeAssemblingInput() {
        properties.setEnabled(false);

        GeneralException exception = assertThrows(
                GeneralException.class,
                () -> service.create(new InsightDTO.CreateRequest(
                        "ISSUE", 88L, List.of("CHIP_MAKER"))));

        assertEquals("COMMON409", exception.getCode().getCode());
        verifyNoInteractions(inputAssembler, persistenceService, agentClient, quotaService);
    }

    private void assertRejected(InsightDTO.CreateRequest request,
                                AgentInsightResponse response) {
        InsightInputAssembler.Snapshot snapshot = snapshot();
        QuotaReservation reservation = new QuotaReservation(
                1L, 42L, "reservation", AgentTask.INSIGHT, AgentPlan.FREE, BigDecimal.ONE);
        when(inputAssembler.assemble(88L)).thenReturn(snapshot);
        when(persistenceService.findCached(any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(planService.get()).thenReturn(new LlmSettingDTO.PlanResponse(
                AgentPlan.FREE, true, PaidExhaustedAction.STUB));
        when(quotaService.reserve(any(), any(), any(), any())).thenReturn(reservation);
        when(agentClient.insight(any())).thenReturn(response);

        assertThrows(GeneralException.class, () -> service.create(request));

        verify(quotaService).completeFailure(
                eq(reservation), any(AgentClientException.class));
        verify(persistenceService, never()).saveGenerated(any(), any(), any(), any(), any());
    }

    private AgentInsightResponse withInsight(AgentInsightResponse response,
                                             AgentInsightResponse.Insight insight) {
        return new AgentInsightResponse(List.of(insight), response.meta());
    }

    private InsightInputAssembler.Snapshot snapshot() {
        return new InsightInputAssembler.Snapshot(
                88L,
                42L,
                "a".repeat(64),
                new AgentInsightRequest.TopicPayload(
                        "HBM", "HBM", List.of("HBM"), List.of(), List.of()),
                List.of(new AgentInsightRequest.FindingPayload(
                        501L,
                        "HBM4 기사",
                        "https://example.com/501",
                        "HBM4 일정 요약",
                        AgentInsightRequest.FindingRole.CURRENT,
                        "2026-09-03",
                        List.of(new AgentInsightRequest.SentencePayload(
                                1, "HBM4 양산 일정이 앞당겨졌다.")))),
                Map.of(501L, 10L));
    }

    private AgentInsightResponse response() {
        return response(Audience.CHIP_MAKER);
    }

    private AgentInsightResponse response(Audience audience) {
        return new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        audience.name(),
                        "양산 일정 변화",
                        List.of(new AgentInsightResponse.Fact(
                                "FACT", "f1", "HBM4 양산 일정이 앞당겨졌다.",
                                501L, List.of(1), "grounded", "원문에서 확인됩니다.")),
                        List.of(new AgentInsightResponse.Implication(
                                "IMPLICATION", "i1", "공급 일정 점검이 필요합니다.",
                                List.of("f1"), "일정이 유지되는 경우",
                                "후속 발표에서 일정이 번복되는 경우")),
                        List.of("후속 양산 발표"),
                        new BigDecimal("0.8"))),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", properties.getInsightPromptVersion(),
                        20L, 10L, new BigDecimal("0.1"), BigDecimal.ONE, false, false));
    }

    private NewsInsight entity(Audience audience) {
        return NewsInsight.builder()
                .id(1L)
                .targetType(AgentTargetType.ISSUE)
                .targetId(88L)
                .audience(audience)
                .headline("양산 일정 변화")
                .facts(List.of(new InsightFact(
                        "FACT", "f1", "확인된 사실", 501L, 10L, List.of(0),
                        "grounded", "원문에서 확인됩니다.")))
                .implications(List.of(new InsightImplication(
                        "IMPLICATION", "i1", "점검이 필요합니다.", List.of("f1"),
                        "일정 유지", "일정 번복")))
                .watchNext(List.of("후속 발표"))
                .confidence(new BigDecimal("0.8"))
                .inputHash("a".repeat(64))
                .promptVersion(properties.getInsightPromptVersion())
                .llmProvider("gemini")
                .llmModel("gemini-test")
                .createdAt(LocalDateTime.of(2026, 9, 2, 12, 0))
                .build();
    }

    private InsightDTO.AudienceInsight dto(Audience audience) {
        return new InsightDTO.AudienceInsight(
                audience, "양산 일정 변화", List.of(), List.of(), List.of(),
                new BigDecimal("0.8"), "gemini", "gemini-test",
                OffsetDateTime.parse("2026-09-02T12:00:00+09:00"));
    }
}
