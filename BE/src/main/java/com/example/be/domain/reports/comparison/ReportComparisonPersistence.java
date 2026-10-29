package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportComparisonPersistence {
    private final NewsReportRepository reports;
    private final ReportComparisonRepository comparisons;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(long reportId) {
        var report = reports.findByIdForUpdate(reportId).orElse(null);
        if (!visible(report) || comparisons.find(reportId).isPresent()) return;
        var current = input(report);
        var selected = baseline(report, current);
        var baseline = selected.report();
        var previous = input(baseline);
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        var status = current == null || !current.hasKnownScopes() || selected.unknown() ? ReportChanges.Status.UNAVAILABLE
                : baseline == null ? ReportChanges.Status.NO_BASELINE
                : previous == null || !previous.hasKnownScopes() ? ReportChanges.Status.UNAVAILABLE
                : ReportChanges.Status.PENDING;
        var result = new ReportChanges(reportId, report.getReportDate(), baseline == null ? null : baseline.getId(),
                baseline == null ? null : baseline.getReportDate(), status, status.message, false, List.of(), List.of());
        ComparisonWork work = status != ReportChanges.Status.PENDING ? null : new ComparisonWork(reportId,
                report.getReportDate(), baseline.getId(), baseline.getReportDate(), previous, current,
                // DAILY captured_at and generatedAt share the reserved generation timestamp.
                // Delayed registration must not import relationships created after that immutable input.
                identityLinks(current, comparisons.inputCapturedAt(reportId).orElse(report.getGeneratedAt())));
        comparisons.insert(result, work, now);
    }

    private Baseline baseline(NewsReport report, ReportComparisonSnapshot current) {
        if (report.getReportScope() != ReportScope.RUN) {
            if (report.getReportDate() == null) return new Baseline(null, true);
            // Keep the existing DAILY query for historical compatibility; WEEKLY must separate topic/null.
            if (report.getReportScope() == ReportScope.DAILY) return new Baseline(reports
                    .findFirstByReportScopeAndReportDateBeforeAndReportStatusNotAndDeletedAtIsNullOrderByReportDateDescIdDesc(
                            ReportScope.DAILY, report.getReportDate(), ReportStatus.PENDING).orElse(null), false);
            return new Baseline(reports.findPreviousComparisonReports(report.getReportScope(), report.getTopicId(),
                    report.getReportDate(), org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().orElse(null), false);
        }
        if (current == null || current.period() == null || current.period().runStartedAt() == null
                || !current.hasKnownScopes()) return new Baseline(null, true);
        var topicIds = current.scopesByTopic().keySet();
        LocalDateTime before = current.period().runStartedAt();
        long beforeId = report.getRunId();
        for (int page = 0; page < 100; page++) {
            var candidates = comparisons.previousRuns(before, beforeId);
            if (candidates.isEmpty()) return new Baseline(null, false);
            for (var candidate : candidates) {
                if (!candidate.known() && candidate.topicIds().stream().anyMatch(topicIds::contains))
                    return new Baseline(null, true);
                if (candidate.known() && candidate.savedTopicIds().equals(topicIds)) {
                    var previous = candidate.reportId() == null ? null : reports.findById(candidate.reportId()).orElse(null);
                    return new Baseline(visible(previous) && previous.getReportScope() == ReportScope.RUN ? previous : null, false);
                }
            }
            var last = candidates.getLast();
            before = last.startedAt();
            beforeId = last.runId();
            if (candidates.size() < 64) return new Baseline(null, false);
        }
        return new Baseline(null, true);
    }
    private record Baseline(NewsReport report, boolean unknown) { }

    private ReportComparisonSnapshot input(NewsReport report) {
        if (report == null) return null;
        try {
            return comparisons.findInput(report.getId())
                    .map(snapshot -> snapshot.reflected(report.getReflectedFindingIds(),
                            report.getReportStatus() == ReportStatus.GENERATED || report.getReportStatus() == ReportStatus.MOCK))
                    .map(snapshot -> report.getReportScope() == ReportScope.WEEKLY ? snapshot.latestPerIssue() : snapshot).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("보고서 비교 스냅샷을 사용할 수 없습니다. reportId={}", report.getId(), exception);
            return null;
        }
    }

    private List<ComparisonWork.IdentityLink> identityLinks(ReportComparisonSnapshot current, LocalDateTime capturedAt) {
        List<ComparisonWork.IdentityLink> result = new ArrayList<>();
        for (var issue : current.issues()) {
            long currentId = issue.side().issueId();
            Set<Long> visited = new HashSet<>(Set.of(currentId));
            var pending = new ArrayDeque<Long>();
            pending.add(currentId);
            boolean ambiguous = false;
            while (!pending.isEmpty()) {
                long target = pending.removeFirst();
                var parents = comparisons.mergeParents(target, capturedAt);
                if (parents.size() > 100 || visited.size() > 100) { ambiguous = true; break; }
                for (var parent : parents) {
                    if (!visited.add(parent.previousIssueId())) { ambiguous = true; continue; }
                    result.add(new ComparisonWork.IdentityLink(parent.previousIssueId(), currentId, "MERGED"));
                    pending.add(parent.previousIssueId());
                }
            }
            var refutations = comparisons.refutedIssues(currentId, capturedAt);
            if (refutations.size() > 100) ambiguous = true;
            else result.addAll(refutations);
            if (ambiguous) result.add(new ComparisonWork.IdentityLink(currentId, currentId, "AMBIGUOUS"));
        }
        return List.copyOf(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReportComparisonRepository.Job> claim(long reportId) {
        if (!comparisons.claim(reportId, LocalDateTime.now(ApiTimeZone.ZONE))) return Optional.empty();
        var job = comparisons.find(reportId).orElseThrow();
        if (!visible(reports.findById(reportId).orElse(null))
                || job.work() == null || !visible(reports.findById(job.work().baseReportId()).orElse(null))) {
            comparisons.finish(reportId, job.result().withStatus(ReportChanges.Status.UNAVAILABLE), LocalDateTime.now(ApiTimeZone.ZONE));
            return Optional.empty();
        }
        return Optional.of(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long reportId, ReportChanges result) {
        // A hidden report must not enter a newly published comparison, even if hidden during the provider call.
        var report = reports.findByIdForUpdate(reportId).orElse(null);
        var baseline = result.baseReportId() == null ? null : reports.findByIdForUpdate(result.baseReportId()).orElse(null);
        if (!visible(report) || !visible(baseline)) result = result.withStatus(ReportChanges.Status.UNAVAILABLE);
        comparisons.finish(reportId, result, LocalDateTime.now(ApiTimeZone.ZONE));
    }

    static boolean visible(NewsReport report) {
        return report != null && report.getReportStatus() != ReportStatus.PENDING && report.getDeletedAt() == null;
    }
}
