package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.repository.WeeklyReportJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WeeklyReportPersistenceService {
    private final DailyReportJdbcRepository creationLock;
    private final WeeklyReportJdbcRepository weeklyRepository;
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevancePolicy;
    private final ReportComparisonRepository comparisons;

    @Transactional
    public Reservation reserve(LocalDate monday, LocalDateTime now) {
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY || !monday.plusDays(6).isBefore(now.toLocalDate())) {
            throw new IllegalArgumentException("주간 보고서는 종료된 월요일~일요일 기간만 집계할 수 있습니다.");
        }
        // Shared with DAILY reservations so two schedulers cannot select a half-reserved source set.
        creationLock.lockCreation();
        NewsReport existing = reports.findByReportScopeAndReportDate(ReportScope.WEEKLY, monday).orElse(null);
        if (existing != null) return new Reservation(existing.getId(), false, existing.getWeeklyInput());
        if (weeklyRepository.hasUnfinishedInputs(monday, now.toLocalDate())) return Reservation.deferred();
        List<NewsReport> sources = reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6)).stream()
                .filter(report -> report.getDeletedAt() == null).toList();
        if (sources.isEmpty() || sources.stream().anyMatch(report -> report.getReportStatus() == ReportStatus.PENDING)) {
            return Reservation.deferred();
        }
        List<LocalDate> sourceDates = sources.stream().map(NewsReport::getReportDate).toList();
        List<LocalDate> missingDates = monday.datesUntil(monday.plusWeeks(1))
                .filter(date -> !sourceDates.contains(date)).toList();
        WeeklyReportInput input = new WeeklyReportInput(monday, monday.plusDays(6),
                sources.stream().map(this::snapshot).toList(), missingDates);
        NewsReport report = reports.saveAndFlush(NewsReport.builder()
                .reportScope(ReportScope.WEEKLY).reportDate(monday).reportEndDate(monday.plusDays(6))
                .sourceReportIds(sources.stream().map(NewsReport::getId).toList())
                .sourceReportDates(sourceDates).missingReportDates(missingDates)
                .sourceReportCount((long) sources.size()).weeklyInput(input)
                .sourceRunIds(sources.stream().flatMap(source -> source.getSourceRunIds().stream()).distinct().toList())
                .collectionContexts(sources.stream().flatMap(source -> source.getCollectionContexts().stream())
                        .distinct().toList())
                .reflectedFindingIds(input.sourceFindingIds()).coverageRecorded(true)
                .comparisonInputUsable(false)
                .title(input.title()).markdownBody("보고서 생성이 진행 중입니다.").modelName("pending-report-v1")
                .reportStatus(ReportStatus.PENDING).generatedAt(now).build());
        return new Reservation(report.getId(), true, input);
    }

    private WeeklyReportInput.DailySource snapshot(NewsReport source) {
        var visible = ReportFindings.loadVisible(source, findings, relevancePolicy);
        List<Long> allowed = visible.findings().stream().map(Finding::getId).toList();
        ReportContent content = source.getStructuredContent();
        String markdown = source.getMarkdownBody();
        if (visible.filtered()) {
            // Do not reconstruct claims from live findings: retain only fully supported saved DAILY sections.
            markdown = "";
            if (content != null) {
                var events = content.importantEvents().stream()
                        .filter(event -> !event.sourceFindingIds().isEmpty() && allowed.containsAll(event.sourceFindingIds())).toList();
                var watchItems = content.watchItems().stream()
                        .filter(item -> !item.sourceFindingIds().isEmpty() && allowed.containsAll(item.sourceFindingIds())).toList();
                content = new ReportContent(events.stream().map(ReportContent.ImportantEvent::summaryKo).limit(3).toList(),
                        events, watchItems, List.of("현재 본문과 관련도를 확인할 수 있는 저장된 일일 보고서 내용만 포함했습니다."));
            }
        }
        var snapshot = comparisons.findInput(source.getId()).map(saved -> new ReportComparisonSnapshot(
                saved.version(), saved.issues().stream().filter(issue -> allowed.contains(issue.findingId())).toList(),
                saved.scopes())).orElse(null);
        return new WeeklyReportInput.DailySource(source.getId(), source.getReportDate(), source.getTitle(),
                markdown, content, allowed, snapshot);
    }

    public record Reservation(Long reportId, boolean owner, WeeklyReportInput input) {
        static Reservation deferred() { return new Reservation(null, false, null); }
    }
}
