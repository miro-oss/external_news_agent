package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        properties.setInsightPromptVersion("insight.ko.v1+perspective.ko.v1");
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
                AgentTargetType.ISSUE, 88L, snapshot.inputHash(), response))
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
        verify(quotaService).completeSuccess(reservation, BigDecimal.ONE);
        verify(runRecorder).recordInsightSuccess(
                eq(42L), eq(88L), any(), eq(response), any(LocalDateTime.class));
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
        when(persistenceService.saveGenerated(any(), any(), any(), any()))
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
                        List.of(new AgentInsightRequest.SentencePayload(
                                1, "HBM4 양산 일정이 앞당겨졌다.")))));
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
                        "FACT", "f1", "확인된 사실", 501L, List.of(0),
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
