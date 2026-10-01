package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightVersionTest {
    private static final String HASH = "a".repeat(64);
    private final NewsReportInsightRepository repository = mock(NewsReportInsightRepository.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final ReportInsightSnapshotAssembler assembler = mock(ReportInsightSnapshotAssembler.class);
    private final AgentClient client = mock(AgentClient.class);
    private final AgentQuotaService quota = mock(AgentQuotaService.class);
    private final LlmPlanService plans = mock(LlmPlanService.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final List<NewsReportInsight> stored = new ArrayList<>();
    private final ReportInsightPersistenceService persistence = new ReportInsightPersistenceService(
            repository, reports, new ObjectMapper(), assembler);
    private ReportInsightService service;

    @BeforeEach void setup() {
        var properties = new AgentProperties();
        properties.setEnabled(true);
        service = new ReportInsightService(properties, assembler, persistence, new ReportInsightValidator(),
                client, quota, plans, recorder, new ReportInsightExecutionRecorder(recorder, quota, persistence));
        when(assembler.assemble(10L)).thenReturn(snapshot(HASH));
        when(assembler.assembleForRead(10L)).thenReturn(snapshot(HASH));
        when(reports.findByIdForUpdate(10L)).thenReturn(Optional.of(NewsReport.builder()
                .id(10L).reportStatus(ReportStatus.GENERATED).build()));
        stored.add(NewsReportInsight.builder().reportId(10L).audience(Audience.CHIP_MAKER).inputHash(HASH)
                .promptVersion("report-insight.ko.v3").rubricVersion("report-importance.v2")
                .payloadJson("{}").build());
        stored.add(NewsReportInsight.builder().reportId(10L).audience(Audience.CHIP_MAKER).inputHash(HASH)
                .promptVersion("report-insight.ko.v4").rubricVersion("report-importance.v3")
                .payloadJson("{}").build());
        stored.add(NewsReportInsight.builder().reportId(10L).audience(Audience.CHIP_MAKER).inputHash(HASH)
                .promptVersion("report-insight.ko.v5").rubricVersion("report-importance.v4")
                .payloadJson("{}").build());
        stored.add(NewsReportInsight.builder().reportId(10L).audience(Audience.CHIP_MAKER).inputHash(HASH)
                .promptVersion("report-insight.ko.v6").rubricVersion("report-importance.v5")
                .payloadJson("{}").build());
        stored.add(NewsReportInsight.builder().reportId(10L).audience(Audience.CHIP_MAKER).inputHash(HASH)
                .promptVersion("report-insight.ko.v7").rubricVersion("report-importance.v5")
                .payloadJson("{}").build());
        when(repository.findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(
                anyLong(), anyString(), anyString(), anyString(), anyCollection())).thenAnswer(call -> {
            Collection<Audience> audiences = call.getArgument(4);
            return stored.stream().filter(row -> row.getReportId().equals(call.getArgument(0))
                    && row.getInputHash().equals(call.getArgument(1))
                    && row.getPromptVersion().equals(call.getArgument(2))
                    && row.getRubricVersion().equals(call.getArgument(3))
                    && audiences.contains(row.getAudience())).toList();
        });
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            NewsReportInsight row = call.getArgument(0);
            stored.add(row);
            return row;
        });
        when(plans.get()).thenReturn(new LlmSettingDTO.PlanResponse(AgentPlan.PAID, false, PaidExhaustedAction.STUB));
        when(quota.reserveReportInsight(any(), anyString(), any())).thenAnswer(call -> new QuotaReservation(
                1L, 20L, call.getArgument(1), AgentTask.INSIGHT, AgentPlan.PAID, BigDecimal.ONE));
        when(client.reportInsight(any())).thenReturn(response());
        when(recorder.recordReportInsightSuccess(any(), any(), any(), any(), any())).thenReturn(true);
    }

    @Test void getWithOnlyLegacyVersionReturnsNotFoundWithoutProviderQuotaOrWrites() {
        var missing = assertThrows(GeneralException.class, () -> service.get(10L, "CHIP_MAKER"));
        assertEquals(GeneralErrorCode.NOT_FOUND, missing.getCode());
        assertEquals("저장된 리포트 관점 인사이트가 없습니다.", missing.getMessage());
        verify(repository).findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(
                10L, HASH, "report-insight.ko.v8", "report-importance.v5", List.of(Audience.CHIP_MAKER));
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(client, quota, plans, recorder, reports);
        assertEquals(5, stored.size());
    }

    @Test void createBypassesLegacyCacheAndStoresV8WithDistinctReservationThenGetReadsIt() {
        var result = service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER")));
        assertFalse(result.cached());
        assertEquals("report-insight.ko.v8", result.promptVersion());
        assertEquals("report-importance.v5", result.rubricVersion());
        assertEquals(6, stored.size());
        assertEquals("report-insight.ko.v7", stored.get(4).getPromptVersion());
        assertEquals("report-insight.ko.v6", stored.get(3).getPromptVersion());
        assertEquals("report-insight.ko.v5", stored.get(2).getPromptVersion());
        assertEquals("report-insight.ko.v4", stored.get(1).getPromptVersion());
        assertEquals("report-insight.ko.v3", stored.getFirst().getPromptVersion());
        var generated = stored.getLast();
        assertEquals("report-insight.ko.v8", generated.getPromptVersion());
        assertEquals("report-importance.v5", generated.getRubricVersion());
        assertEquals(result.insights().getFirst(), persistence.toDto(generated));
        var request = ArgumentCaptor.forClass(AgentReportInsightRequest.class);
        verify(client).reportInsight(request.capture());
        String expectedKey = "report-insight:10:" + HASH + ":report-insight.ko.v8:report-importance.v5:CHIP_MAKER";
        assertEquals(expectedKey, request.getValue().idempotencyKey());
        verify(quota).reserveReportInsight(20L, expectedKey, AgentPlan.PAID);
        var context = ArgumentCaptor.forClass(ReportInsightAuditContext.class);
        verify(recorder).recordReportInsightSuccess(eq(20L), any(), any(), context.capture(), any());
        assertEquals("report-insight.ko.v8", context.getValue().promptVersion());
        assertEquals("report-importance.v5", context.getValue().rubricVersion());
        clearInvocations(client, quota, plans, recorder, repository, reports);

        var cached = service.get(10L, "CHIP_MAKER");
        assertTrue(cached.cached());
        assertEquals(result.insights(), cached.insights());
        verifyNoInteractions(client, quota, plans, recorder, reports);
        verify(repository, never()).saveAndFlush(any());
    }
}
