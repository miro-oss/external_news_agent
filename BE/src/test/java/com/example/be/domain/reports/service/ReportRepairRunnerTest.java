package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.service.FailedAnalysisRecoveryService;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.global.config.ReportRecoveryMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportRepairRunnerTest {
    private static final String TITLE = "수학의 종말…167년 난제 '리만가설' 푼 클로드";
    private final ReportMaintenanceService reports = mock(ReportMaintenanceService.class);
    private final FailedAnalysisRecoveryService analyses = mock(FailedAnalysisRecoveryService.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final ReportRepairRunner runner = new ReportRepairRunner(reports, analyses, context);

    @BeforeEach void configure() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        runner.reportId = "2310";
        runner.briefingPosition = 3;
        runner.maxArticles = 20;
        runner.recoveryId = "nov23-repair-1";
        runner.promoteEventTitle = TITLE;
        when(reports.inspect(2310)).thenReturn(snapshot("before"));
        when(reports.promote(2310, "before", TITLE, 2)).thenReturn(snapshot("edited"));
    }

    @Test void dryRunReadsOnlyAndClosesContext() {
        runner.recoverAnalyses = true;
        when(analyses.plan(501, 20)).thenReturn(plan(List.of(10L), 0));
        runner.run(null);
        verify(reports).inspect(2310);
        verify(analyses).plan(501, 20);
        verify(reports, never()).promote(anyLong(), anyString(), anyString(), anyInt());
        verify(analyses, never()).recover(anyLong(), anyString(), anyInt());
        verifyNoMoreInteractions(reports, analyses);
        verify(context).close();
    }

    @Test void successfulRecoveryPreservesRequestedEditInGeneratedReport() {
        runner.apply = true; runner.recoverAnalyses = true; runner.regenerateReport = true;
        when(analyses.plan(501, 20)).thenReturn(plan(List.of(10L), 0), plan(List.of(), 0));
        when(analyses.recover(501, "nov23-repair-1", 20)).thenReturn(outcome(List.of(10L), List.of()));
        when(reports.regenerateFallback(2310, "edited", "nov23-repair-1", TITLE, 2))
                .thenReturn(new ReportMaintenanceService.RegenerationResult(true, snapshot("done")));
        runner.run(null);
        var order = inOrder(reports, analyses, context);
        order.verify(reports).promote(2310, "before", TITLE, 2);
        order.verify(analyses).recover(501, "nov23-repair-1", 20);
        order.verify(reports).regenerateFallback(2310, "edited", "nov23-repair-1", TITLE, 2);
        order.verify(context).close();
    }

    @Test void failedAnalysisKeepsSavedEditAndPreventsReportGeneration() {
        runner.apply = true; runner.recoverAnalyses = true; runner.regenerateReport = true;
        when(analyses.plan(501, 20)).thenReturn(plan(List.of(10L), 0));
        when(analyses.recover(501, "nov23-repair-1", 20)).thenReturn(outcome(List.of(), List.of(10L)));
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        verify(reports).promote(2310, "before", TITLE, 2);
        verify(reports, never()).regenerateFallback(anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(context).close();
    }

    @Test void unavailableOriginalInputIsNotMisreportedAsComplete() {
        runner.apply = true; runner.recoverAnalyses = true; runner.regenerateReport = true;
        when(analyses.plan(501, 20)).thenReturn(plan(List.of(), 1));
        when(analyses.recover(501, "nov23-repair-1", 20)).thenReturn(outcome(List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        verify(reports, never()).regenerateFallback(anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(context).close();
    }

    @Test void conflictingModesNeverReachDataAndStillCloseContext() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment()
                .withProperty(ReportRecoveryMode.RUN_IDS_PROPERTY, "501"));
        assertThrows(IllegalArgumentException.class, () -> runner.run(null));
        verifyNoInteractions(reports, analyses);
        verify(context).close();
    }

    @Test void unknownEventIsRejectedBeforeAnyMutationOrAiCall() {
        runner.apply = true; runner.promoteEventTitle = "absent";
        assertThrows(IllegalArgumentException.class, () -> runner.run(null));
        verify(reports, never()).promote(anyLong(), anyString(), anyString(), anyInt());
        verifyNoInteractions(analyses);
        verify(context).close();
    }

    private ReportMaintenanceService.Snapshot snapshot(String fingerprint) {
        return new ReportMaintenanceService.Snapshot(2310, 501L, fingerprint, ReportStatus.FALLBACK,
                LocalDateTime.of(2026, 11, 23, 7, 27), List.of("First", "PDD Kit", "토큰값", TITLE));
    }
    private FailedAnalysisRecoveryService.Plan plan(List<Long> ids, int unavailable) {
        return new FailedAnalysisRecoveryService.Plan(501, ids, 1, unavailable);
    }
    private FailedAnalysisRecoveryService.Outcome outcome(List<Long> recovered, List<Long> failed) {
        return new FailedAnalysisRecoveryService.Outcome(501, "nov23-repair-1", recovered, failed, List.of());
    }
}
