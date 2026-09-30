package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.*;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.*;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.*;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightServiceTest {
    AgentProperties properties = new AgentProperties();
    ReportInsightSnapshotAssembler assembler = mock(ReportInsightSnapshotAssembler.class);
    ReportInsightPersistenceService persistence = mock(ReportInsightPersistenceService.class);
    AgentClient client = mock(AgentClient.class);
    AgentQuotaService quota = mock(AgentQuotaService.class);
    LlmPlanService plans = mock(LlmPlanService.class);
    AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    ReportInsightService service = new ReportInsightService(properties, assembler, persistence,
            new ReportInsightValidator(), client, quota, plans, recorder, new ReportInsightExecutionRecorder(recorder, quota, persistence));
    @BeforeEach void setup() {
        properties.setEnabled(true);
        when(recorder.recordReportInsightSuccess(any(), any(), any(), any(), any())).thenReturn(true);
        when(recorder.recordReportInsightFailure(any(), any(), any(), any(), any())).thenReturn(true);
        when(assembler.assemble(10L)).thenReturn(snapshot("a".repeat(64)));
        when(persistence.findCached(anyLong(), anyString(), anyCollection())).thenReturn(List.of());
        when(plans.get()).thenReturn(new LlmSettingDTO.PlanResponse(AgentPlan.PAID, false, PaidExhaustedAction.STUB));
        when(quota.reserveReportInsight(any(), anyString(), any())).thenAnswer(call ->
                new QuotaReservation(1L, 20L, call.getArgument(1), AgentTask.INSIGHT, AgentPlan.PAID, BigDecimal.ONE));
        when(client.reportInsight(any())).thenAnswer(call -> {
            AgentReportInsightRequest request = call.getArgument(0);
            var original = response();
            var insight = original.insights().getFirst();
            return new AgentReportInsightResponse(List.of(new AgentReportInsightResponse.Insight(request.audiences().getFirst(),
                    insight.headline(), insight.overview(), insight.assessments(), insight.implications(), insight.watchItems())), original.meta());
        });
        when(persistence.saveGenerated(any(), any())).thenAnswer(call -> {
            AgentReportInsightResponse response = call.getArgument(1);
            return List.of(row(Audience.valueOf(response.insights().getFirst().audience())));
        });
        when(persistence.toDto(any())).thenAnswer(call -> dto(((NewsReportInsight) call.getArgument(0)).getAudience()));
    }
    @Test void eachMissingAudienceHasDistinctSingleAudienceReservationAndProviderExecution() {
        var result = service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "IT_INFRA")));
        assertFalse(result.cached());
        assertEquals(List.of(Audience.CHIP_MAKER, Audience.IT_INFRA), result.insights().stream().map(ReportInsightDTO.AudienceInsight::audience).toList());
        var requests = ArgumentCaptor.forClass(AgentReportInsightRequest.class);
        verify(client, times(2)).reportInsight(requests.capture());
        assertEquals(List.of("CHIP_MAKER"), requests.getAllValues().getFirst().audiences());
        assertEquals(List.of("IT_INFRA"), requests.getAllValues().getLast().audiences());
        assertNotEquals(requests.getAllValues().getFirst().idempotencyKey(), requests.getAllValues().getLast().idempotencyKey());
        verify(quota, times(2)).reserveReportInsight(eq(20L), anyString(), eq(AgentPlan.PAID));
        verify(quota, times(2)).completeSuccess(any(), eq(BigDecimal.ONE));
        verify(recorder, times(2)).recordReportInsightSuccess(eq(20L), any(), any(), any(), any());
    }
    @Test void getReadsExactCurrentHashWithoutProviderQuotaOrAuditWrites() {
        when(persistence.findCached(10L, "a".repeat(64), List.of(Audience.CHIP_MAKER))).thenReturn(List.of(row(Audience.CHIP_MAKER)));
        assertTrue(service.get(10L, "CHIP_MAKER").cached());
        verifyNoInteractions(client, quota, plans, recorder);
        when(assembler.assemble(10L)).thenReturn(snapshot("b".repeat(64)));
        var missing = assertThrows(GeneralException.class, () -> service.get(10L, "CHIP_MAKER"));
        assertEquals("저장된 리포트 관점 인사이트가 없습니다.", missing.getMessage());
        verifyNoInteractions(client, quota, plans, recorder);
    }
    @Test void cachedCreateUsesNoQuotaAndRecordsOnlyCacheAudit() {
        when(persistence.findCached(10L, "a".repeat(64), List.of(Audience.CHIP_MAKER))).thenReturn(List.of(row(Audience.CHIP_MAKER)));
        assertTrue(service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))).cached());
        verifyNoInteractions(client, quota, plans);
        verify(recorder).recordReportInsightCacheHit(eq(20L), eq(10L), any(), any());
    }
    @Test void invalidAudiencePositiveIdAndMissingEvidenceFailBeforeQuota() {
        assertThrows(AudienceException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("OTHER"))));
        assertThrows(AudienceException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "chip_maker"))));
        assertEquals("reportId는 양수여야 합니다.", assertThrows(GeneralException.class, () -> service.get(0L, "CHIP_MAKER")).getMessage());
        when(assembler.assemble(10L)).thenThrow(new GeneralException(com.example.be.global.apiPayload.code.GeneralErrorCode.CONFLICT, "근거 없음"));
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verifyNoInteractions(client, quota, plans, recorder);
    }
    @Test void overReservationResponseIsObservedFailureBeforePersistenceAndNeverSuccessAudit() {
        doReturn(response(response().insights().getFirst().assessments(), BigDecimal.TWO)).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeObservedFailure(any(), failure.capture());
        assertEquals("BUDGET_EXCEEDED", failure.getValue().getCode());
        assertEquals(BigDecimal.TWO, failure.getValue().getUsage().credits());
        verify(persistence, never()).saveGenerated(any(), any());
        verify(recorder, never()).recordReportInsightSuccess(any(), any(), any(), any(), any());
    }
    @Test void sourceRaceAndSettlementErrorsPreserveObservedUsageWithoutSuccessAudit() {
        doThrow(new IllegalStateException("근거 변경")).when(persistence).saveGenerated(any(), any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verify(quota).completeObservedFailure(any(), argThat(failure -> failure.getUsage().credits().equals(BigDecimal.ONE)));
        verify(recorder, never()).recordReportInsightSuccess(any(), any(), any(), any(), any());
    }
    @Test void knownSchemaRepairFailureChargesAreNotReleasedThroughGenericFailurePath() {
        var failure = new AgentClientException("SCHEMA_VIOLATION", "repair 실패", null,
                new AgentClientException.Usage(100L, 50L, BigDecimal.ONE, BigDecimal.ONE));
        doThrow(failure).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verify(quota).completeObservedFailure(any(), same(failure));
        verify(quota, never()).completeFailure(any(), anyString());
    }
    @Test void earlierPerspectiveRemainsSavedWhenLaterPerspectiveFails() {
        doReturn(response()).doThrow(new AgentClientException("PROVIDER_UNAVAILABLE", "실패")).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "IT_INFRA"))));
        verify(persistence, times(1)).saveGenerated(any(), any());
        verify(quota, times(1)).completeSuccess(any(), any());
        verify(quota, times(1)).completeObservedFailure(any(), any());
    }
    private NewsReportInsight row(Audience audience) { return NewsReportInsight.builder().reportId(10L).audience(audience).inputHash("a".repeat(64)).build(); }
    private ReportInsightDTO.AudienceInsight dto(Audience audience) { return new ReportInsightDTO.AudienceInsight(audience,
            "제목", "high", List.of(), List.of(), List.of(), List.of(), List.of(), "openai", "model", OffsetDateTime.now()); }
}
