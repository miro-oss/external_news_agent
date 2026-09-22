package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.converter.WeeklyReportInputConverter;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyReportGeneratorTest {
    private final LocalDate monday = LocalDate.of(2026, 9, 7);
    private final WeeklyReportGenerator generator = new WeeklyReportGenerator();

    @Test
    void savedIssueIdentityGroupsDifferentDailyFindingsAndKeepsDatedProgression() {
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(
                source(10, monday, 1, "공장 검토", "A사는 공장 신설을 검토했다."),
                source(11, monday.plusDays(2), 2, "공장 승인", "A사는 공장 신설을 승인했다.")), List.of(monday.plusDays(1)));
        var document = generator.generate(input);
        assertEquals(ReportStatus.FALLBACK, document.status());
        assertEquals(1, document.structuredContent().importantEvents().size());
        var event = document.structuredContent().importantEvents().getFirst();
        assertEquals(List.of(1L, 2L), event.sourceFindingIds());
        assertTrue(event.summaryKo().contains("2026-09-07: A사는 공장 신설을 검토했다."));
        assertTrue(event.summaryKo().contains("2026-09-09: A사는 공장 신설을 승인했다."));
        assertTrue(document.structuredContent().sourceNotes().stream().anyMatch(note -> note.contains("2026-09-08")));
        assertFalse(document.markdownBody().contains("전주 대비"));
        var codec = new WeeklyReportInputConverter();
        assertEquals(input, codec.convertToEntityAttribute(codec.convertToDatabaseColumn(input)));
    }

    @Test
    void genericIdenticalWordingDoesNotMergeKnownDifferentIssues() {
        var first = source(10, monday, 1, "설비 증설", "설비 증설이 발표됐다.");
        var other = source(11, monday.plusDays(1), 2, "설비 증설", "설비 증설이 발표됐다.");
        var different = new ReportComparisonSnapshot(1, List.of(new ReportComparisonSnapshot.Issue(
                2, "hash", "v1", "date", new ReportChanges.Side(88, 1, "설비 증설", "설비 증설이 발표됐다.", List.of()))), List.of());
        var second = new WeeklyReportInput.DailySource(other.reportId(), other.reportDate(), other.title(),
                other.markdownBody(), other.structuredContent(), other.reflectedFindingIds(), different);
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(first, second), List.of());
        assertEquals(2, generator.generate(input).structuredContent().importantEvents().size());
        var legacy = new WeeklyReportInput.DailySource(12L, monday.plusDays(2), "설비 증설", "원문",
                new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent(
                        "설비 증설", "설비 증설이 발표됐다.", "", List.of(3L))), List.of(), List.of()), List.of(3L));
        var mixed = new WeeklyReportInput(monday, monday.plusDays(6), List.of(first, second, legacy), List.of());
        var events = generator.generate(mixed).structuredContent().importantEvents();
        assertEquals(3, events.size());
        assertTrue(events.stream().noneMatch(event -> event.sourceFindingIds().containsAll(List.of(1L, 2L))));
        var legacyFirst = new WeeklyReportInput(monday, monday.plusDays(6), List.of(legacy, first, second), List.of());
        assertEquals(3, generator.generate(legacyFirst).structuredContent().importantEvents().size());
    }

    @Test
    void unsupportedReferencesAndLegacyUnstructuredTextDoNotBecomeWeeklyClaims() {
        ReportContent content = new ReportContent(List.of("입증 안 된 요약"), List.of(
                new ReportContent.ImportantEvent("지원 없음", "없는 근거", "", List.of(999L))), List.of(), List.of());
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(
                new WeeklyReportInput.DailySource(1L, monday, "제목", "원본", content, List.of(1L)),
                new WeeklyReportInput.DailySource(2L, monday.plusDays(1), "과거", "검증되지 않은 옛 본문", null, List.of(2L))), List.of());
        var document = generator.generate(input);
        assertTrue(document.reflectedFindingIds().isEmpty());
        assertTrue(document.structuredContent().importantEvents().isEmpty());
        assertFalse(document.markdownBody().contains("입증 안 된 요약"));
        assertFalse(document.markdownBody().contains("검증되지 않은 옛 본문"));
        assertTrue(document.structuredContent().sourceNotes().stream().anyMatch(note -> note.contains("과거 일일 보고서")));
    }

    private WeeklyReportInput.DailySource source(long reportId, LocalDate date, long findingId, String title, String text) {
        ReportContent content = new ReportContent(List.of(text),
                List.of(new ReportContent.ImportantEvent(title, text, "", List.of(findingId))), List.of(), List.of());
        var side = new ReportChanges.Side(77, 1, title, text, List.of());
        var evidence = new ReportComparisonSnapshot(1, List.of(new ReportComparisonSnapshot.Issue(
                findingId, "hash", "v1", date.toString(), side)), List.of());
        return new WeeklyReportInput.DailySource(reportId, date, title, text, content, List.of(findingId), evidence);
    }
}
