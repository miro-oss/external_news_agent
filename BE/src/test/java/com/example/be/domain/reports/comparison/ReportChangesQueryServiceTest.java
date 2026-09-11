package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Optional;
import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportChangesQueryServiceTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final ReportChangesQueryService query = new ReportChangesQueryService(reports, comparisons);

    private void visible(ReportScope scope) {
        when(reports.findByIdAndReportStatusNot(101L, ReportStatus.PENDING)).thenReturn(Optional.of(
                NewsReport.builder().id(101L).reportScope(scope).reportDate(LocalDate.of(2026, 9, 10)).build()));
    }

    @Test void missingHiddenOrPendingReportUsesReport404() {
        assertThrows(ReportException.class, () -> query.get(101));
        verifyNoInteractions(comparisons);
    }

    @Test void runReportIsNotApplicableAndLegacyDailyDoesNotBackfillLiveEvidence() {
        visible(ReportScope.RUN);
        assertEquals(ReportChanges.Status.NOT_APPLICABLE, query.get(101).status());
        verifyNoInteractions(comparisons);
        visible(ReportScope.DAILY);
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        verify(comparisons).findInput(101);
        verify(comparisons, never()).insert(any(), any(), any());
    }

    @Test void savedResultIsReadOnlyAndHiddenBaselineQuotesAreNeverReturned() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "과거 근거")), snapshot(side(1, 20, "현재 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        assertEquals("과거 근거", query.get(101).items().getFirst().previous().claims().getFirst().evidence().getFirst().text());
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING)).thenReturn(Optional.empty());
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        assertTrue(query.get(101).items().isEmpty());
        verify(comparisons, never()).claim(anyLong(), any());
        verify(comparisons, never()).insert(any(), any(), any());
    }

    @Test void staleRunningStateUsesPersistedFailureRatherThanStaleResultStatus() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "이전")), snapshot(side(1, 20, "현재")));
        var pending = new ReportComparisonEngine().prepare(work).result().withStatus(ReportChanges.Status.PENDING);
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.FAILED, work, pending)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        assertEquals(ReportChanges.Status.FAILED, query.get(101).status());
        assertEquals(ReportChanges.Status.FAILED.message, query.get(101).message());
    }
}
