package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.global.database.OracleInClause;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ReportChangesQueryService {
    private final NewsReportRepository reports;
    private final ReportComparisonRepository comparisons;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevancePolicy;
    private final ReportEventFeedbackProjection feedbackProjection;
    private ReportKeywordChangesService keywordChanges;

    @Autowired
    public void setKeywordChangesService(ReportKeywordChangesService keywordChanges) {
        this.keywordChanges = keywordChanges;
    }

    @Transactional(readOnly = true)
    public ReportChanges get(long reportId) {
        var report = reports.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        return project(report, null, false, false);
    }

    /** One projected object is shared by the email and its PDF; neither path asks AI to compare again. */
    @Transactional(readOnly = true)
    public ReportChanges projectForDelivery(NewsReport report, ReportEventFeedbackProjection.View view,
                                           boolean hasPersonalPolicies) {
        try {
            return project(report, view, true, hasPersonalPolicies);
        } catch (RuntimeException unavailable) {
            // Optional comparison availability must not stop an otherwise deliverable report.
            return state(report, ReportChanges.Status.UNAVAILABLE).withPresentation(periods(report, null, null),
                    ReportKeywordChangesService.unavailable("NO_SNAPSHOT"));
        }
    }

    private ReportChanges project(NewsReport report, ReportEventFeedbackProjection.View suppliedView,
                                  boolean delivery, boolean hasPersonalPolicies) {
        var job = comparisons.find(report.getId()).orElse(null);
        if (job == null) {
            var status = comparisons.findInput(report.getId()).isPresent()
                    ? ReportChanges.Status.PENDING : ReportChanges.Status.UNAVAILABLE;
            return state(report, status).withPresentation(periods(report, null, null),
                    ReportKeywordChangesService.unavailable(hasPersonalPolicies ? "PERSONALIZATION_UNVERIFIED"
                            : status == ReportChanges.Status.PENDING ? "COMPARISON_NOT_READY" : "NO_SNAPSHOT"));
        }
        var base = job.result().baseReportId() == null ? null : reports.findByIdAndReportStatusNot(
                job.result().baseReportId(), ReportStatus.PENDING).filter(value -> value.getDeletedAt() == null).orElse(null);
        var result = job.result().withStatus(job.status()).withPresentation(periods(report, base, job.work()), null);
        if (report.getDeletedAt() != null || report.getReportStatus() == ReportStatus.PENDING
                || job.result().baseReportId() != null && base == null) return unavailable(result, "FILTERED_INPUT");
        if (!report.isComparisonInputUsable() || base != null && !base.isComparisonInputUsable())
            return unavailable(result, "NO_SNAPSHOT");
        if (hasPersonalPolicies) return unavailable(result, "PERSONALIZATION_UNVERIFIED");
        var currentView = suppliedView == null ? view(report, delivery) : suppliedView;
        var previousView = base == null ? null : view(base, delivery);
        if (excluded(currentView) || previousView != null && excluded(previousView)) return unavailable(result, "FILTERED_INPUT");
        if (job.status() == ReportChanges.Status.READY && !eligible(report, base, result))
            return unavailable(result, "FILTERED_INPUT");
        var statistics = job.status() == ReportChanges.Status.NO_BASELINE
                ? ReportKeywordChangesService.unavailable("NO_BASELINE")
                : job.status() != ReportChanges.Status.READY
                    ? ReportKeywordChangesService.unavailable("COMPARISON_NOT_READY")
                    : keywordChanges == null ? ReportKeywordChangesService.unavailable("NO_SNAPSHOT")
                        : keywordChanges.project(base, report, job.work(), previousView, currentView, delivery);
        return result.withPresentation(result.periods(), statistics);
    }

    private boolean eligible(NewsReport report, NewsReport base, ReportChanges result) {
        Set<Long> required = new LinkedHashSet<>(report.getReflectedFindingIds());
        if (base != null) required.addAll(base.getReflectedFindingIds());
        result.items().stream().flatMap(item -> Stream.of(item.previous(), item.current()))
                .filter(Objects::nonNull).flatMap(side -> side.claims().stream())
                .flatMap(claim -> claim.evidence().stream()).map(ReportChanges.Evidence::findingId).forEach(required::add);
        if (required.stream().anyMatch(report.getCompanySuppressedFindingIds()::contains)
                || base != null && required.stream().anyMatch(base.getCompanySuppressedFindingIds()::contains)) return false;
        List<Finding> loaded = OracleInClause.batches(required).stream()
                .flatMap(ids -> findings.findForReportByIdIn(ids).stream()).toList();
        Set<Long> available = relevancePolicy.filterFindings(loaded).stream()
                .filter(ReportFindings::hasFullText).map(Finding::getId).collect(Collectors.toSet());
        Map<Long, Set<Long>> acceptedTopics = relevancePolicy.relevantTopicIdsByFinding(loaded);
        boolean acceptedInSavedTopics = result.items().stream()
                .flatMap(item -> Stream.of(item.previous(), item.current())).filter(Objects::nonNull)
                .allMatch(side -> side.claims().stream().flatMap(claim -> claim.evidence().stream())
                        .allMatch(evidence -> !acceptedTopics.containsKey(evidence.findingId())
                                || acceptedTopics.get(evidence.findingId()).contains(side.topicId())));
        // Check only visibility against current data. Quoted text remains the frozen historical evidence.
        return available.containsAll(required) && acceptedInSavedTopics;
    }

    private ReportEventFeedbackProjection.View view(NewsReport report, boolean delivery) {
        var visible = ReportFindings.loadVisible(report, findings, relevancePolicy);
        var reviews = feedbackProjection.reviews(report.getId());
        // Always project, including WEEKLY inherited DAILY exclusions without any direct review.
        return delivery ? feedbackProjection.projectForDelivery(report, visible, reviews)
                : feedbackProjection.project(report, visible, reviews);
    }

    private static boolean excluded(ReportEventFeedbackProjection.View view) {
        return view == null || view.excludedEvents() || view.sourceFiltered() || !view.excludedEvidenceFindingIds().isEmpty();
    }

    private static ReportChanges state(NewsReport report, ReportChanges.Status state) {
        return new ReportChanges(report.getId(), report.getReportDate(), null, null, state, state.message,
                false, List.of(), List.of());
    }

    private static ReportChanges unavailable(ReportChanges result, String reason) {
        var state = ReportChanges.Status.UNAVAILABLE;
        return new ReportChanges(result.reportId(), result.reportDate(), result.baseReportId(), result.baseReportDate(),
                state, state.message, false, List.of(), List.of(), result.periods(), ReportKeywordChangesService.unavailable(reason));
    }

    private static ReportChanges.Periods periods(NewsReport current, NewsReport previous, ComparisonWork work) {
        var currentPeriod = work == null ? null : work.current().period();
        var priorPeriod = work == null ? null : work.previous().period();
        return new ReportChanges.Periods(current.getReportScope().name(), label(previous, priorPeriod), label(current, currentPeriod));
    }

    private static String label(NewsReport report, ReportComparisonSnapshot.Period period) {
        if (report == null) return null;
        ReportScope scope = period == null ? report.getReportScope() : period.scope();
        if (scope == ReportScope.RUN) {
            var started = period == null ? report.getRun() == null ? null : report.getRun().getStartedAt() : period.runStartedAt();
            return started == null ? "실행 #" + report.getRunId()
                    : started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 실행";
        }
        var from = period == null ? report.getReportDate() : period.from();
        var through = period == null ? report.getReportEndDate() : period.through();
        return from == null ? "기간 자료 없음" : through == null || through.equals(from) ? from.toString() : from + " ~ " + through;
    }
}
