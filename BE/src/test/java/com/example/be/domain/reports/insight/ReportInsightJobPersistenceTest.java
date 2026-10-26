package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightJobPersistenceTest {
    final NewsReportRepository reports = mock(NewsReportRepository.class);
    final ReportInsightJobRepository jobs = mock(ReportInsightJobRepository.class);
    final ReportInsightJobPersistence persistence = new ReportInsightJobPersistence(reports, jobs);

    @ParameterizedTest @EnumSource(ReportScope.class)
    void everyCompletedScopeRegistersAllFourPerspectives(ReportScope scope) {
        var report = NewsReport.builder().id(17L).reportScope(scope).automaticInsightsRequested(true)
                .reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
        persistence.enqueue(17L);
        for (var audience : Audience.values()) verify(jobs).enqueue(eq(17L), eq(audience), any());
    }

    @Test void legacyHiddenPendingAndMissingReportsAreNotBackfilled() {
        for (var report : new NewsReport[]{
                NewsReport.builder().id(17L).reportStatus(ReportStatus.GENERATED).build(),
                NewsReport.builder().id(17L).automaticInsightsRequested(true).reportStatus(ReportStatus.PENDING).build(),
                NewsReport.builder().id(17L).automaticInsightsRequested(true).deletedAt(LocalDateTime.now()).build()}) {
            when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
            persistence.enqueue(17L);
        }
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.empty());
        persistence.enqueue(17L);
        verifyNoInteractions(jobs);
    }

    @Test void hiddenReportClaimBecomesTerminalWithoutProviderWork() {
        var job = new ReportInsightJobRepository.Job(17L, Audience.CHIP_MAKER);
        when(jobs.claim(eq(job), any())).thenReturn(true);
        when(reports.findById(17L)).thenReturn(Optional.of(NewsReport.builder().id(17L)
                .deletedAt(LocalDateTime.now()).build()));
        assertFalse(persistence.claim(job));
        verify(jobs).finish(eq(job), eq(false), any());
    }
}
