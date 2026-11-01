package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.comparison.*;
import com.example.be.domain.reports.coverage.*;
import com.example.be.domain.reports.converter.ReportCoverageSnapshotConverter;
import com.example.be.domain.reports.converter.ReportKeywordSnapshotConverter;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.keyword.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.database.OracleInClause;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.io.StringReader;
import java.util.*;

/** Opt-in repair of missing reading data, never report regeneration or a GET side effect. */
@Service
@RequiredArgsConstructor
public class ReportSnapshotRecoveryService {
    public static final int MAX_REPORTS = 300;
    public static final String RECOVERED = "저장된 수집 조건과 기사·분석 자료로 복원한 집계입니다.";
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final CollectionRunRepository runs;
    private final DailyReportSelector daily;
    private final TopicWeeklyReportSelector topicWeekly;
    private final ReportCoverageCapture coverage;
    private final ReportKeywordCapture keywords;
    private final ReportComparisonRepository comparisons;
    private final ReportComparisonCriteria criteria;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional
    public List<Outcome> recover(List<Long> reportIds, boolean apply, LocalDateTime now) {
        if (reportIds == null || reportIds.isEmpty() || reportIds.size() > MAX_REPORTS
                || reportIds.stream().anyMatch(id -> id == null || id < 1))
            throw new IllegalArgumentException("복원할 보고서 ID는 1~300개여야 합니다.");
        Map<Long, NewsReport> ordered = new LinkedHashMap<>();
        for (Long id : new LinkedHashSet<>(reportIds)) collect(id, ordered, new HashSet<>());
        Set<Long> invalidated = ordered.values().stream().filter(report -> !report.isComparisonInputUsable())
                .map(NewsReport::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> staleDependents = new HashSet<>();
        for (Long id : invalidated) staleDependents.addAll(jdbc.queryForList(
                "SELECT report_id FROM news_report_comparisons WHERE base_report_id = ? AND status = 'READY'", Long.class, id));
        var outside = staleDependents.stream().filter(id -> !ordered.containsKey(id)).sorted().toList();
        if (!outside.isEmpty()) throw new IllegalStateException("무효화된 기준을 참조하는 보고서를 복원 범위에 추가해야 합니다: " + outside);
        Set<Long> resetReady = new HashSet<>(staleDependents);
        // Validate before mutating any row, including private DAILY dependencies.
        for (var report : ordered.values()) {
            if (report.getReportStatus() == ReportStatus.PENDING)
                throw new IllegalStateException("생성 중인 보고서는 복원할 수 없습니다. reportId=" + report.getId());
            var job = comparisons.find(report.getId()).orElse(null);
            if (job != null && (job.status() == ReportChanges.Status.RUNNING || job.status() == ReportChanges.Status.PENDING))
                throw new IllegalStateException("비교 작업이 진행 중입니다. reportId=" + report.getId());
            if (job != null && job.status() == ReportChanges.Status.READY && report.getReportScope() != ReportScope.RUN) {
                var expectedDate = report.getReportScope() == ReportScope.DAILY
                        ? report.getReportDate().minusDays(1) : report.getReportDate().minusWeeks(1);
                var base = job.result().baseReportId() == null ? null
                        : reports.findById(job.result().baseReportId()).orElse(null);
                if (base == null || base.getReportScope() != report.getReportScope()
                        || !Objects.equals(base.getReportDate(), expectedDate)
                        || !Objects.equals(base.getTopicId(), report.getTopicId())) resetReady.add(report.getId());
            }
        }
        Map<Long, NewsReport> reconstructed = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (var original : ordered.values()) {
            var input = shell(original);
            ReportCoverageSnapshot capturedCoverage = original.getReportCoverageSnapshot();
            ReportKeywordSnapshot capturedKeywords = original.getReportKeywordSnapshot();
            boolean needsCoverage = capturedCoverage == null;
            boolean needsKeywords = needsKeywordRecovery(capturedKeywords);
            if (original.getReportScope() == ReportScope.WEEKLY && original.getTopicId() == null) {
                if (original.getWeeklyInput() == null) throw new IllegalStateException("주간 원본 입력이 없습니다. reportId=" + original.getId());
                var sources = sourceIds(original).stream().map(reconstructed::get).toList();
                if (needsCoverage) capturedCoverage = coverage.mergeWeekly(input, sources, now);
                if (needsCoverage) capturedCoverage = withOriginalBindings(original, capturedCoverage);
                input.freezeReportCoverageSnapshot(capturedCoverage);
                if (needsKeywords) {
                    var recorded = criteria.applied(original);
                    capturedKeywords = original.isCompanySelectionRecorded() && recorded.isEmpty()
                            ? keywords.mergeRecoveredWeekly(input, sources, now, Set.of())
                            : selectedKeywords(input, now);
                }
            } else if (original.getReportScope() == ReportScope.RUN) {
                if (needsCoverage) capturedCoverage = coverage.captureRun(input, now);
                input.freezeReportCoverageSnapshot(capturedCoverage);
                if (needsKeywords) capturedKeywords = keywords.captureRun(input, now);
            } else if (original.getReportScope() == ReportScope.DAILY) {
                var includedRuns = Set.copyOf(original.getSourceRunIds());
                var bounded = daily.prepare(original.getReportDate(), 50, includedRuns);
                if (needsCoverage) capturedCoverage = coverage.captureDaily(input, bounded, now);
                input.freezeReportCoverageSnapshot(capturedCoverage);
                if (needsKeywords) capturedKeywords = keywords.captureDaily(input, bounded, now);
            } else {
                var includedRuns = Set.copyOf(original.getSourceRunIds());
                var prepared = original.getReportDate().datesUntil(original.getReportEndDate().plusDays(1))
                        .map(date -> topicWeekly.prepare(original.getTopicId(), original.getTopicName(), date, includedRuns)).toList();
                if (needsCoverage) capturedCoverage = coverage.captureTopicWeek(input, prepared, now, includedRuns);
                input.freezeReportCoverageSnapshot(capturedCoverage);
                if (needsKeywords) capturedKeywords = keywords.captureTopicWeek(input, prepared, now);
            }
            if (needsCoverage && capturedCoverage != null) capturedCoverage = recovered(capturedCoverage);
            if (needsKeywords && capturedKeywords != null) capturedKeywords = recovered(capturedKeywords);
            input = shell(original, capturedCoverage, capturedKeywords);
            var existingInput = comparisons.findInput(original.getId()).orElse(null);
            ReportComparisonSnapshot comparison = existingInput;
            if (comparison == null && capturedCoverage != null) comparison = comparison(input, capturedCoverage);
            boolean usable = comparison != null && comparison.hasKnownScopes();
            if (apply) {
                // Do not dirty a managed NewsReport: Hibernate's full-row update would reserialize
                // untouched legacy CLOBs with today's converters. Repair only the intended columns.
                if (needsCoverage && capturedCoverage != null) writeSnapshot(original.getId(),
                        "report_coverage_snapshot", new ReportCoverageSnapshotConverter().convertToDatabaseColumn(capturedCoverage));
                if (needsKeywords && capturedKeywords != null) writeSnapshot(original.getId(),
                        "report_keyword_snapshot", new ReportKeywordSnapshotConverter().convertToDatabaseColumn(capturedKeywords));
                if (existingInput == null && usable) {
                    jdbc.update("DELETE FROM news_report_comparison_inputs WHERE report_id = ?", original.getId());
                    jdbc.update("UPDATE news_reports SET comparison_input_usable_yn = 'Y' WHERE id = ?", original.getId());
                    comparisons.captureInput(original, comparison, now);
                    jdbc.update("DELETE FROM news_report_comparisons WHERE report_id = ? AND status = 'READY'", original.getId());
                }
                if (resetReady.contains(original.getId()))
                    jdbc.update("DELETE FROM news_report_comparisons WHERE report_id = ? AND status = 'READY'", original.getId());
                if (usable) jdbc.update("""
                        DELETE FROM news_report_comparisons WHERE report_id = ?
                        AND status IN ('NO_BASELINE', 'UNAVAILABLE', 'NOT_APPLICABLE', 'FAILED')
                        """, original.getId());
            }
            reconstructed.put(original.getId(), input);
            outcomes.add(new Outcome(original.getId(), original.getReportScope(), original.getTopicId(),
                    original.getDeletedAt() != null, needsCoverage && capturedCoverage != null,
                    needsKeywords && capturedKeywords != null && capturedKeywords.unavailableReason() == null,
                    capturedKeywords == null ? "NO_SNAPSHOT" : capturedKeywords.unavailableReason(),
                    capturedKeywords == null ? 0 : capturedKeywords.candidates().size(), existingInput == null && usable));
        }
        return List.copyOf(outcomes);
    }

    static boolean needsKeywordRecovery(ReportKeywordSnapshot snapshot) {
        return snapshot == null || snapshot.unavailableReason() != null
                && Set.of("NO_SNAPSHOT", "INCOMPLETE_SOURCE", "MISSING_COLLECTION_CONTEXT", "INVALID_SNAPSHOT")
                        .contains(snapshot.unavailableReason());
    }

    private void writeSnapshot(long reportId, String column, String value) {
        if (!Set.of("report_coverage_snapshot", "report_keyword_snapshot").contains(column))
            throw new IllegalArgumentException("복원할 수 없는 보고서 열입니다.");
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("UPDATE news_reports SET " + column + " = ? WHERE id = ?");
            statement.setCharacterStream(1, new StringReader(value), value.length());
            statement.setLong(2, reportId);
            return statement;
        });
    }

    private void collect(long id, Map<Long, NewsReport> ordered, Set<Long> visiting) {
        if (ordered.containsKey(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("주간 보고서 원본이 순환합니다.");
        var report = reports.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("보고서 없음: " + id));
        // The database lock lasts until transaction end, while the legacy entity stays outside dirty checking.
        entityManager.detach(report);
        for (Long source : sourceIds(report)) collect(source, ordered, visiting);
        if (ordered.size() >= MAX_REPORTS) throw new IllegalArgumentException("원본 포함 복원 범위는 300개 이하이어야 합니다.");
        ordered.put(id, report);
        visiting.remove(id);
    }

    private static List<Long> sourceIds(NewsReport report) {
        if (report.getReportScope() != ReportScope.WEEKLY || report.getTopicId() != null) return List.of();
        return report.getWeeklyInput() == null ? report.getSourceReportIds()
                : report.getWeeklyInput().sources().stream().map(WeeklyReportInput.DailySource::reportId)
                        .filter(Objects::nonNull).distinct().toList();
    }

    private ReportComparisonSnapshot comparison(NewsReport report, ReportCoverageSnapshot captured) {
        if (report.getWeeklyInput() != null && report.getWeeklyInput().sources().stream()
                .allMatch(source -> source.evidenceSnapshot() != null)) {
            var saved = report.getWeeklyInput().sources().stream()
                    .sorted(Comparator.comparing(WeeklyReportInput.DailySource::reportDate))
                    .map(WeeklyReportInput.DailySource::evidenceSnapshot).toList();
            var restored = new ReportComparisonSnapshot(1, saved.stream().flatMap(value -> value.issues().stream()).toList(),
                    report.getCollectionContexts()).withReportContext(report);
            if (restored.issues().stream().map(ReportComparisonSnapshot.Issue::findingId).collect(java.util.stream.Collectors.toSet())
                    .containsAll(report.getReflectedFindingIds())) return withConditions(report, restored);
        }
        Set<Long> ids = new LinkedHashSet<>(report.getReflectedFindingIds());
        if (ids.isEmpty()) captured.bindings().forEach(binding -> ids.add(binding.findingId()));
        var stored = OracleInClause.batches(ids).stream().flatMap(batch -> findings.findForReportByIdIn(batch).stream())
                .sorted(Comparator.comparing((Finding finding) -> finding.getRun().getStartedAt()).thenComparing(Finding::getId)).toList();
        if (stored.size() != ids.size()) return null;
        try {
            return withConditions(report, ReportComparisonSnapshot.captureByBindings(stored, captured.bindings()).withReportContext(report));
        } catch (IllegalStateException incomplete) { return null; }
    }

    private ReportKeywordSnapshot selectedKeywords(NewsReport report, LocalDateTime now) {
        var ids = report.getReflectedFindingIds();
        var selected = OracleInClause.batches(ids).stream()
                .flatMap(batch -> findings.findForReportByIdIn(batch).stream()).toList();
        if (selected.size() != new HashSet<>(ids).size()) throw new IllegalStateException("보고서 선정 근거가 누락되었습니다. reportId=" + report.getId());
        var captured = keywords.captureDaily(report, new DailyReportSelector.Prepared(selected, 0, 0, Map.of(), 50), now);
        return new ReportKeywordSnapshot(ReportKeywordSnapshot.RECOVERED_SELECTED_RULE_VERSION,
                captured.capturedAt(), captured.periodLabel() + " · 보고서 포함 이슈 기준",
                captured.unavailableReason(), captured.definitions(), captured.candidates());
    }

    private static ReportCoverageSnapshot withOriginalBindings(NewsReport report, ReportCoverageSnapshot captured) {
        if (captured == null || report.getWeeklyInput() == null) return captured;
        Map<Long, ReportCoverageSnapshot.Binding> byFinding = new LinkedHashMap<>();
        captured.bindings().forEach(binding -> byFinding.put(binding.findingId(), binding));
        report.getWeeklyInput().sources().stream().filter(source -> source.evidenceSnapshot() != null)
                .flatMap(source -> source.evidenceSnapshot().issues().stream()).forEach(issue ->
                        byFinding.put(issue.findingId(), new ReportCoverageSnapshot.Binding(issue.findingId(),
                                issue.side().issueId(), issue.side().topicId())));
        return new ReportCoverageSnapshot(captured.ruleVersion(), captured.capturedAt(), captured.periodLabel(),
                captured.observations(), List.copyOf(byFinding.values()));
    }

    private ReportComparisonSnapshot withConditions(NewsReport report, ReportComparisonSnapshot snapshot) {
        var comparisonRunIds = report.getReportScope() == ReportScope.RUN ? sourceRunIds(report)
                : comparisons.periodRunIds(report.getReportDate().atStartOfDay(),
                        (report.getReportScope() == ReportScope.DAILY ? report.getReportDate()
                                : report.getReportEndDate()).plusDays(1).atStartOfDay());
        var sourceRuns = OracleInClause.batches(comparisonRunIds).stream()
                .flatMap(batch -> runs.findAllById(batch).stream()).toList();
        return snapshot.withSourceConditions(ReportComparisonSnapshot.sourceConditions(sourceRuns, report.getTopicId()));
    }

    private NewsReport shell(NewsReport original) { return shell(original, null, null); }

    private NewsReport shell(NewsReport original, ReportCoverageSnapshot captured, ReportKeywordSnapshot matched) {
        var contexts = original.getCollectionContexts();
        if (contexts.isEmpty()) contexts = OracleInClause.batches(sourceRunIds(original)).stream()
                .flatMap(batch -> runs.findAllById(batch).stream()).map(ReportCollectionContext::from).toList();
        return NewsReport.builder().id(original.getId()).run(original.getRun()).reportScope(original.getReportScope())
                .reportDate(original.getReportDate()).reportEndDate(original.getReportEndDate()).topicId(original.getTopicId())
                .topicName(original.getTopicName()).sourceRunIds(original.getSourceRunIds()).weeklyInput(original.getWeeklyInput())
                .collectionContexts(contexts).sourceReportIds(original.getSourceReportIds())
                .missingReportDates(original.getMissingReportDates()).reflectedFindingIds(original.getReflectedFindingIds())
                .companySuppressedFindingIds(original.getCompanySuppressedFindingIds()).reportStatus(ReportStatus.PENDING)
                .reportCoverageSnapshot(captured).reportKeywordSnapshot(matched).build();
    }

    private static List<Long> sourceRunIds(NewsReport report) {
        return report.getReportScope() == ReportScope.RUN ? List.of(report.getRunId()) : report.getSourceRunIds();
    }

    private static ReportCoverageSnapshot recovered(ReportCoverageSnapshot value) {
        return new ReportCoverageSnapshot(value.ruleVersion(), value.capturedAt(), value.periodLabel() + " · 저장 자료 복원",
                value.observations(), value.bindings());
    }

    private static ReportKeywordSnapshot recovered(ReportKeywordSnapshot value) {
        return new ReportKeywordSnapshot(value.ruleVersion(), value.capturedAt(), value.periodLabel() + " · 저장 자료 복원",
                value.unavailableReason(), value.definitions(), value.candidates());
    }

    public record Outcome(long reportId, ReportScope scope, Long topicId, boolean hiddenDependency,
                          boolean coverageRestored, boolean keywordsRestored, String keywordUnavailableReason,
                          int candidateCount, boolean comparisonRestored) { }
}
