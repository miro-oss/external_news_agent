package com.example.be.domain.reports.service;

import com.example.be.domain.reports.coverage.ReportCoverageCapture;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final ReportEventFeedbackProjection feedbackProjection = mock(ReportEventFeedbackProjection.class);
    private final DailyReportFeedbackExclusions feedbackExclusions = mock(DailyReportFeedbackExclusions.class);
    private final WeeklyReportPersistenceService service = new WeeklyReportPersistenceService(
            lock, weekly, reports, findings, relevance, comparisons, feedbackProjection, feedbackExclusions, mock(ReportCoverageCapture.class), mock(com.example.be.domain.reports.keyword.ReportKeywordCapture.class));
    private final LocalDate monday = LocalDate.of(2026, 9, 7);
    private final LocalDateTime now = monday.plusWeeks(1).atTime(0, 5);

    @BeforeEach
    void prepare() {
        when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0));
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(feedbackProjection.project(any(), any(), anyList())).thenAnswer(call -> {
            NewsReport report = call.getArgument(0);
            ReportFindings.Visible visible = call.getArgument(1);
            return new ReportEventFeedbackProjection.View(visible.findings(), ReportReadingContent.from(report, visible), false);
        });
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
        verifyNoInteractions(feedbackProjection);
    }

    @Test
    void confirmedDailyEventsUseProjectedSummaryWatchItemsAndEvidenceInGenerationInputs() {
        Finding valid = finding(1);
        Finding rejected = finding(2);
        NewsReport source = source(10, monday, List.of(1L, 2L));
        source.recordStructuredContent(content("오류 주장", List.of(1L, 2L)));
        ReportContent projected = content("검증된 저장 주장", List.of(1L));
        when(findings.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(valid, rejected));
        doReturn(new ReportEventFeedbackProjection.View(List.of(valid), new ReportReadingContent("검증된 본문", projected), true))
                .when(feedbackProjection).project(eq(source), any(), anyList());
        when(comparisons.findInput(10)).thenReturn(Optional.of(evidence(List.of(1L, 2L))));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(source));

        var input = service.reserve(monday, now).input();
        var snapshot = input.sources().getFirst();

        assertEquals(List.of(1L), snapshot.reflectedFindingIds());
        assertEquals(List.of(1L), snapshot.evidenceSnapshot().issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList());
        assertEquals(projected, snapshot.structuredContent());
        assertEquals("검증된 본문", snapshot.markdownBody());
        assertFalse(new WeeklyReportGenerator().generate(input).markdownBody().contains("오류 주장"));
        assertEquals(List.of(1L, 2L), source.getReflectedFindingIds());
        assertEquals(List.of("오류 주장"), source.getStructuredContent().executiveSummary());
        verify(feedbackProjection).reviews(10);
    }

    @Test
    void inheritedDailyErrorFiltersAllDaysAndPreservesFrozenSourceMetadata() {
        Finding valid = finding(1);
        Finding firstError = finding(2);
        Finding laterError = finding(3);
        NewsReport first = source(10, monday, List.of(1L, 2L));
        ReportContent original = new ReportContent(List.of("오류가 섞인 요약"), List.of(
                new ReportContent.ImportantEvent("유지", "검증된 저장 주장", "", List.of(1L)),
                new ReportContent.ImportantEvent("오류", "제외할 오류 주장", "", List.of(2L))), List.of(
                new ReportContent.WatchItem("유지 관찰", "확인", List.of(1L)),
                new ReportContent.WatchItem("오류 관찰", "제외", List.of(2L)),
                new ReportContent.WatchItem("출처 없는 관찰", "제외", List.of())), List.of("오류를 되풀이한 참고"));
        first.recordStructuredContent(original);
        NewsReport later = source(11, monday.plusDays(1), List.of(3L));
        later.recordStructuredContent(content("다른 날짜의 같은 오류", List.of(3L)));
        when(findings.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(valid, firstError));
        when(findings.findForReportByIdIn(List.of(3L))).thenReturn(List.of(laterError));
        when(comparisons.findInput(10)).thenReturn(Optional.of(evidence(List.of(1L, 2L))));
        when(comparisons.findInput(11)).thenReturn(Optional.of(evidence(List.of(3L))));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(first, later));
        when(feedbackExclusions.forReport(any())).thenReturn(Set.of(2L, 3L));

        var input = service.reserve(monday, now).input();

        assertEquals(List.of(1L), input.sourceFindingIds());
        assertEquals(List.of(10L, 11L), input.sources().stream().map(WeeklyReportInput.DailySource::reportId).toList());
        assertEquals(List.of(monday, monday.plusDays(1)), input.sources().stream().map(WeeklyReportInput.DailySource::reportDate).toList());
        var kept = input.sources().getFirst();
        assertEquals(List.of("검증된 저장 주장"), kept.structuredContent().executiveSummary());
        assertEquals(1, kept.structuredContent().importantEvents().size());
        assertEquals(List.of("유지 관찰"), kept.structuredContent().watchItems().stream().map(ReportContent.WatchItem::topic).toList());
        assertTrue(kept.structuredContent().sourceNotes().isEmpty());
        assertEquals("", kept.markdownBody());
        assertEquals(List.of(1L), kept.evidenceSnapshot().issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList());
        assertTrue(input.sources().getLast().structuredContent().importantEvents().isEmpty());
        assertTrue(input.sources().getLast().evidenceSnapshot().issues().isEmpty());
        assertFalse(new WeeklyReportGenerator().generate(input).markdownBody().contains("오류"));
        assertSame(original, first.getStructuredContent());
        assertEquals(List.of(1L, 2L), first.getReflectedFindingIds());
        var candidate = org.mockito.ArgumentCaptor.forClass(NewsReport.class);
        verify(feedbackExclusions).forReport(candidate.capture());
        assertEquals(ReportScope.WEEKLY, candidate.getValue().getReportScope());
        assertEquals(monday, candidate.getValue().getReportDate());
        assertEquals(monday.plusDays(6), candidate.getValue().getReportEndDate());
        assertEquals(List.of(1L, 2L, 3L), candidate.getValue().getReflectedFindingIds());
    }

    @Test
    void unaffectedDailySnapshotKeepsStoredContentAndMarkdown() {
        NewsReport source = source(10, monday, List.of());
        ReportContent original = content("저장된 내용", List.of());
        source.recordStructuredContent(original);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(source));

        var snapshot = service.reserve(monday, now).input().sources().getFirst();

        assertSame(original, snapshot.structuredContent());
        assertEquals(source.getMarkdownBody(), snapshot.markdownBody());
    }

    @Test
    void removedCompoundEventCannotLeaveOrphanEvidenceOrRepeatItsClaimInWatchItems() {
        List<Finding> sourceFindings = List.of(finding(1), finding(2), finding(3));
        NewsReport source = source(10, monday, List.of(1L, 2L, 3L));
        source.recordStructuredContent(new ReportContent(List.of("오류 요약"), List.of(
                new ReportContent.ImportantEvent("복합 오류", "제외할 복합 주장", "", List.of(1L, 2L)),
                new ReportContent.ImportantEvent("유지", "검증된 저장 주장", "", List.of(3L))), List.of(
                new ReportContent.WatchItem("오류 반복", "제외할 복합 주장", List.of(1L)),
                new ReportContent.WatchItem("유지 관찰", "확인", List.of(3L))), List.of()));
        when(findings.findForReportByIdIn(List.of(1L, 2L, 3L))).thenReturn(sourceFindings);
        when(comparisons.findInput(10)).thenReturn(Optional.of(evidence(List.of(1L, 2L, 3L))));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(source));
        when(feedbackExclusions.forReport(any())).thenReturn(Set.of(2L));

        var input = service.reserve(monday, now).input();
        var snapshot = input.sources().getFirst();

        assertEquals(List.of(3L), input.sourceFindingIds());
        assertEquals(List.of(3L), snapshot.evidenceSnapshot().issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList());
        assertEquals(List.of("유지 관찰"), snapshot.structuredContent().watchItems().stream().map(ReportContent.WatchItem::topic).toList());
        assertFalse(new WeeklyReportGenerator().generate(input).markdownBody().contains("복합 주장"));
        assertEquals(List.of(1L, 2L, 3L), source.getReflectedFindingIds());
    }

    @Test
    void removedWatchPrunesOnlyOrphanCompanionsAndKeepsSourcesUsedByValidEventsOrWatches() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        List<Finding> sourceFindings = ids.stream().map(this::finding).toList();
        NewsReport source = source(10, monday, ids);
        source.recordStructuredContent(new ReportContent(List.of("오류 요약"), List.of(
                new ReportContent.ImportantEvent("유지 이벤트", "검증된 저장 주장", "", List.of(4L))), List.of(
                new ReportContent.WatchItem("오류 관찰", "제외할 복합 주장", List.of(1L, 2L)),
                new ReportContent.WatchItem("공유 근거 오류 관찰", "제외할 복합 주장", List.of(1L, 3L, 4L)),
                new ReportContent.WatchItem("유지 관찰", "독립적으로 확인할 내용", List.of(3L))), List.of()));
        when(findings.findForReportByIdIn(ids)).thenReturn(sourceFindings);
        when(comparisons.findInput(10)).thenReturn(Optional.of(evidence(ids)));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(source));
        when(feedbackExclusions.forReport(any())).thenReturn(Set.of(1L));

        var input = service.reserve(monday, now).input();
        var snapshot = input.sources().getFirst();

        assertEquals(List.of(3L, 4L), input.sourceFindingIds());
        assertEquals(List.of(3L, 4L), snapshot.evidenceSnapshot().issues().stream()
                .map(ReportComparisonSnapshot.Issue::findingId).toList());
        assertEquals(List.of("유지 이벤트"), snapshot.structuredContent().importantEvents().stream()
                .map(ReportContent.ImportantEvent::title).toList());
        assertEquals(List.of("유지 관찰"), snapshot.structuredContent().watchItems().stream()
                .map(ReportContent.WatchItem::topic).toList());
        var generated = new WeeklyReportGenerator().generate(input);
        assertEquals(Set.of(3L, 4L), Set.copyOf(generated.reflectedFindingIds()));
        assertFalse(generated.markdownBody().contains("복합 주장"));
        assertEquals(ids, source.getReflectedFindingIds());
    }

    private Finding finding(long id) {
        Finding finding = mock(Finding.class);
        Article article = mock(Article.class);
        when(finding.getId()).thenReturn(id);
        when(finding.getArticle()).thenReturn(article);
        when(article.hasFullText()).thenReturn(true);
        return finding;
    }

    private ReportContent content(String claim, List<Long> ids) {
        return new ReportContent(List.of(claim), List.of(new ReportContent.ImportantEvent("저장된 사건", claim, "", ids)),
                List.of(new ReportContent.WatchItem("저장된 관찰", claim, ids)), List.of());
    }

    private ReportComparisonSnapshot evidence(List<Long> ids) {
        return new ReportComparisonSnapshot(1, ids.stream().map(id -> new ReportComparisonSnapshot.Issue(
                id, "input", "prompt", monday.toString(), new ReportChanges.Side(id, 1L, "이슈", "근거", List.of()))).toList(), List.of());
    }

    private NewsReport source(long id, LocalDate date, List<Long> ids) {
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportDate(date)
                .sourceRunIds(List.of(100L)).title(date + " 일일 통합").markdownBody("저장된 본문")
                .reflectedFindingIds(ids).reportStatus(ReportStatus.GENERATED).build();
    }
}
