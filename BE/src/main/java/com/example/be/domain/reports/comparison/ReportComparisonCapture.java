package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.service.TopicWeeklyReportSelector;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Captures during reservation only. A comparison failure never prevents the original report. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportComparisonCapture {
    private final ReportComparisonRepository comparisons;
    private final CollectionRunRepository runs;
    private final FindingRepository findings;

    public void run(NewsReport report, LocalDateTime now) {
        safely(report, () -> {
            var coverage = report.getReportCoverageSnapshot();
            if (coverage == null) return;
            var ids = coverage.bindings().stream().map(binding -> binding.findingId()).distinct().toList();
            var candidates = OracleInClause.batches(ids).stream()
                    .flatMap(batch -> findings.findForReportByIdIn(batch).stream()).toList();
            if (candidates.size() != ids.size()) return;
            capture(report, ReportComparisonSnapshot.captureByBindings(candidates, coverage.bindings()), now);
        });
    }

    public void daily(NewsReport report, ReportComparisonSnapshot snapshot, LocalDateTime now) {
        safely(report, () -> capture(report, snapshot, now));
    }

    public void topicWeek(NewsReport report, List<TopicWeeklyReportSelector.Prepared> prepared, LocalDateTime now) {
        safely(report, () -> {
            // Preserve all frozen candidates: company selection can promote an issue below the preview top-N.
            var issues = prepared.stream().sorted(Comparator.comparing(TopicWeeklyReportSelector.Prepared::date))
                    .flatMap(day -> ReportComparisonSnapshot.captureByFinding(day.candidates(), day.issues()).issues().stream()).toList();
            capture(report, new ReportComparisonSnapshot(1, issues, report.getCollectionContexts()), now);
        });
    }

    public void weekly(NewsReport report, LocalDateTime now) {
        safely(report, () -> {
            var sources = report.getWeeklyInput().sources().stream()
                    .sorted(Comparator.comparing(WeeklyReportInput.DailySource::reportDate)).toList();
            if (sources.stream().anyMatch(source -> source.evidenceSnapshot() == null)) return;
            var snapshots = sources.stream().map(WeeklyReportInput.DailySource::evidenceSnapshot).toList();
            var conditions = snapshots.stream().anyMatch(snapshot -> snapshot.sourceConditions() == null) ? null
                    : snapshots.stream().flatMap(snapshot -> snapshot.sourceConditions().stream()).distinct().toList();
            var snapshot = new ReportComparisonSnapshot(1, snapshots.stream().flatMap(value -> value.issues().stream()).toList(),
                    snapshots.stream().flatMap(value -> value.scopes().stream()).distinct().toList(),
                    ReportComparisonSnapshot.Period.from(report), conditions);
            comparisons.captureInput(report, snapshot, now);
        });
    }

    private void capture(NewsReport report, ReportComparisonSnapshot snapshot, LocalDateTime now) {
        if (snapshot == null) return;
        var endDate = report.getReportScope() == ReportScope.DAILY ? report.getReportDate() : report.getReportEndDate();
        var sourceIds = report.getRunId() != null ? List.of(report.getRunId())
                : comparisons.periodRunIds(report.getReportDate().atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        var sourceRuns = OracleInClause.batches(sourceIds).stream().flatMap(ids -> runs.findAllById(ids).stream()).toList();
        var conditions = sourceRuns.size() == sourceIds.size()
                ? ReportComparisonSnapshot.sourceConditions(sourceRuns, report.getTopicId()) : null;
        var contexts = sourceRuns.stream().map(ReportCollectionContext::from)
                .map(context -> report.getTopicId() == null ? context : new ReportCollectionContext(context.runId(),
                        context.topics().stream().filter(topic -> report.getTopicId().equals(topic.topicId())).toList()))
                .filter(context -> !context.topics().isEmpty()).toList();
        comparisons.captureInput(report, new ReportComparisonSnapshot(snapshot.version(), snapshot.issues(), contexts,
                ReportComparisonSnapshot.Period.from(report), conditions), now);
    }

    private void safely(NewsReport report, Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) {
            log.warn("보고서 비교 입력 저장 실패. reportId={}", report.getId(), exception);
        }
    }
}
