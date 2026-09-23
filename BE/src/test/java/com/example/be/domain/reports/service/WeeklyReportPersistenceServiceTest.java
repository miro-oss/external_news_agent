package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyReportPersistenceServiceTest {
    private final DailyReportJdbcRepository lock = mock(DailyReportJdbcRepository.class);
    private final WeeklyReportJdbcRepository weekly = mock(WeeklyReportJdbcRepository.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final WeeklyReportPersistenceService service = new WeeklyReportPersistenceService(
            lock, weekly, reports, findings, relevance, comparisons);
    private final LocalDate monday = LocalDate.of(2026, 9, 7);
    private final LocalDateTime now = monday.plusWeeks(1).atTime(0, 5);

    @BeforeEach
    void prepare() {
        when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0));
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void preservesChronologicalSourceMetadataAndMissingCalendarDates() {
        NewsReport first = source(10, monday, List.of());
        NewsReport last = source(16, monday.plusDays(6), List.of());
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(first, last));
        var reservation = service.reserve(monday, now);
        assertTrue(reservation.owner());
        var capture = org.mockito.ArgumentCaptor.forClass(NewsReport.class);
        verify(reports).saveAndFlush(capture.capture());
        NewsReport saved = capture.getValue();
        assertEquals(List.of(10L, 16L), saved.getSourceReportIds());
        assertEquals(List.of(monday, monday.plusDays(6)), saved.getSourceReportDates());
        assertEquals(monday.plusDays(1).datesUntil(monday.plusDays(6)).toList(), saved.getMissingReportDates());
        assertEquals(2, saved.getSourceReportCount());
        assertEquals(List.of(100L), saved.getSourceRunIds());
        assertFalse(saved.isComparisonInputUsable());
        assertEquals("2026-09-07 ~ 2026-09-13 주간 통합 뉴스 보고서", saved.getTitle());
    }

    @Test
    void hiddenDailySourcesAreExcludedWithoutRecreation() {
        NewsReport visible = source(10, monday, List.of());
        NewsReport hidden = source(11, monday.plusDays(1), List.of());
        hidden.hide(now.minusMinutes(1));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(visible, hidden));
        var reservation = service.reserve(monday, now);
        assertEquals(List.of(10L), reservation.input().sources().stream().map(WeeklyReportInput.DailySource::reportId).toList());
        assertTrue(reservation.input().missingReportDates().contains(monday.plusDays(1)));
        verify(comparisons, never()).findInput(11);
    }

    @Test
    void unfinishedAndEmptyWeeksDoNotReserve() {
        when(weekly.hasUnfinishedInputs(monday, now.toLocalDate())).thenReturn(true);
        assertNull(service.reserve(monday, now).reportId());
        verify(reports, never()).saveAndFlush(any());
        when(weekly.hasUnfinishedInputs(monday, now.toLocalDate())).thenReturn(false);
        assertNull(service.reserve(monday, now).reportId());
        verify(reports, never()).saveAndFlush(any());
    }

    @Test
    void existingWeekIsIdempotentAndInvalidCalendarDatesAreRejected() {
        when(reports.findByReportScopeAndReportDate(ReportScope.WEEKLY, monday))
                .thenReturn(Optional.of(NewsReport.builder().id(44L).build()));
        assertEquals(44, service.reserve(monday, now).reportId());
        verifyNoInteractions(weekly, findings, comparisons);
        assertThrows(IllegalArgumentException.class, () -> service.reserve(monday.plusDays(1), now));
        assertThrows(IllegalArgumentException.class, () -> service.reserve(monday.plusWeeks(1), now));
    }

    @Test
    void suppressedEvidenceIsRemovedWithoutReadingReplacementFindingClaims() {
        Finding remaining = mock(Finding.class);
        Article article = mock(Article.class);
        when(remaining.getId()).thenReturn(1L);
        when(remaining.getArticle()).thenReturn(article);
        when(article.hasFullText()).thenReturn(true);
        when(findings.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(remaining));
        NewsReport source = source(10, monday, List.of(1L, 2L));
        source.recordStructuredContent(new ReportContent(List.of("가려진 원문 요약"), List.of(
                new ReportContent.ImportantEvent("유지", "당시 저장된 사실", "", List.of(1L)),
                new ReportContent.ImportantEvent("제외", "가려진 사실", "", List.of(1L, 2L))), List.of(), List.of()));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(source));
        var snapshot = service.reserve(monday, now).input().sources().getFirst();
        assertEquals(List.of(1L), snapshot.reflectedFindingIds());
        assertEquals(List.of("당시 저장된 사실"), snapshot.structuredContent().executiveSummary());
        assertEquals(1, snapshot.structuredContent().importantEvents().size());
        assertEquals("", snapshot.markdownBody());
        verify(remaining, never()).getSummary();
    }

    private NewsReport source(long id, LocalDate date, List<Long> ids) {
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportDate(date)
                .sourceRunIds(List.of(100L)).title(date + " 일일 통합").markdownBody("저장된 본문")
                .reflectedFindingIds(ids).reportStatus(ReportStatus.GENERATED).build();
    }
}
