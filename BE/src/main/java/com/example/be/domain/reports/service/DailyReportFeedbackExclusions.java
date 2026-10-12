package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.feedback.model.FeedbackModels.EventContext;
import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Propagate a confirmed DAILY decision using saved issue/topic identities, never mutable memberships. */
@Service
@RequiredArgsConstructor
public class DailyReportFeedbackExclusions {
    private final NewsReportRepository reports;
    private final FeedbackStore feedback;
    private final ReportEventSnapshotFactory snapshots;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final ReportComparisonRepository comparisons;

    public Set<Long> forReport(NewsReport report) {
        Period period = period(report);
        if (period == null || report.getReflectedFindingIds().isEmpty()) return Set.of();
        return matching(report.getReflectedFindingIds(), identities(report), report.getTopicId(),
                rejectedByPeriod(List.of(period)).getOrDefault(period, List.of()));
    }

    /** Delivery omits submitted opinions immediately, without promoting them to factual rejections. */
    public Set<Long> forDelivery(NewsReport report) {
        Period period = period(report);
        if (period == null || report.getReflectedFindingIds().isEmpty()) return Set.of();
        return matching(report.getReflectedFindingIds(), identities(report), report.getTopicId(),
                rejectedByPeriod(List.of(period), true).getOrDefault(period, List.of()));
    }

    /** A list page shares DAILY reads and revision validation between global and topic weeks. */
    public Map<Long, Set<Long>> forReports(Collection<NewsReport> targets) {
        Map<Period, List<NewsReport>> grouped = new LinkedHashMap<>();
        for (NewsReport report : targets) {
            Period period = period(report);
            if (period != null && !report.getReflectedFindingIds().isEmpty()) {
                grouped.computeIfAbsent(period, ignored -> new ArrayList<>()).add(report);
            }
        }
        if (grouped.isEmpty()) return Map.of();
        Map<Period, List<RejectedEvidence>> rejected = rejectedByPeriod(grouped.keySet());
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        grouped.forEach((period, values) -> values.forEach(report -> {
            result.put(report.getId(), matching(report.getReflectedFindingIds(), identities(report),
                    report.getTopicId(), rejected.getOrDefault(period, List.of())));
        }));
        return Collections.unmodifiableMap(result);
    }

    /** Selection runs per day, but a DAILY rejection applies throughout the containing topic week. */
    public Set<Long> forSelection(LocalDate date, ReportComparisonSnapshot evidenceSnapshot) {
        if (date == null || evidenceSnapshot == null || evidenceSnapshot.issues().isEmpty()) return Set.of();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Period period = new Period(monday, monday.plusDays(6));
        Map<Long, Set<IssueIdentity>> identities = new LinkedHashMap<>();
        addIdentities(identities, evidenceSnapshot);
        return matching(evidenceSnapshot.issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList(),
                identities, null, rejectedByPeriod(List.of(period)).getOrDefault(period, List.of()));
    }

    private Map<Period, List<RejectedEvidence>> rejectedByPeriod(Collection<Period> periods) {
        return rejectedByPeriod(periods, false);
    }

    private Map<Period, List<RejectedEvidence>> rejectedByPeriod(Collection<Period> periods, boolean delivery) {
        Map<Period, List<NewsReport>> dailyByPeriod = new LinkedHashMap<>();
        Map<Long, NewsReport> dailyById = new LinkedHashMap<>();
        for (Period period : periods) {
            List<NewsReport> daily = reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                            ReportScope.DAILY, period.from(), period.through()).stream()
                    .filter(report -> report.getReportScope() == ReportScope.DAILY
                            && report.getReportStatus() != ReportStatus.PENDING
                            && report.getReportDate() != null
                            && !report.getReportDate().isBefore(period.from())
                            && !report.getReportDate().isAfter(period.through())).toList();
            // Hiding a report is not a revocation of its completed factual review.
            dailyByPeriod.put(period, daily);
            daily.forEach(report -> dailyById.putIfAbsent(report.getId(), report));
        }
        if (dailyById.isEmpty()) return Map.of();
        Map<Long, List<Feedback>> reviews = delivery ? dailyById.keySet().stream()
                .flatMap(id -> feedback.eventFeedback(id).stream().filter(review -> ReportEventFeedbackProjection.localEvent(review, id)))
                .collect(Collectors.groupingBy(Feedback::reportId))
                : feedback.eventReviewStates(dailyById.keySet()).stream()
                    .filter(ReportEventFeedbackProjection::confirmedError).collect(Collectors.groupingBy(Feedback::reportId));
        Map<Long, List<RejectedEvidence>> rejectedById = new LinkedHashMap<>();
        dailyById.forEach((id, report) -> {
            List<Feedback> decisions = reviews.getOrDefault(id, List.of());
            if (!decisions.isEmpty()) rejectedById.put(id, rejected(report, decisions, delivery));
        });
        Map<Period, List<RejectedEvidence>> result = new LinkedHashMap<>();
        dailyByPeriod.forEach((period, daily) -> result.put(period, daily.stream()
                .flatMap(report -> rejectedById.getOrDefault(report.getId(), List.of()).stream()).toList()));
        return result;
    }

    private List<RejectedEvidence> rejected(NewsReport report, List<Feedback> reviews, boolean delivery) {
        Set<String> keys = reviews.stream().map(Feedback::eventKey).collect(Collectors.toSet());
        var visible = ReportFindings.loadVisible(report, findings, relevance);
        List<EventContext> current = snapshots.capture(report, visible).stream()
                .map(item -> item.event()).filter(java.util.Objects::nonNull).toList();
        List<EventContext> events = new ArrayList<>(current.stream().filter(event -> keys.contains(event.key())).toList());
        if (delivery) {
            Set<String> currentKeys = current.stream().map(EventContext::key).collect(Collectors.toSet());
            reviews.stream().filter(review -> !currentKeys.contains(review.eventKey()))
                    .map(review -> ReportEventFeedbackProjection.submittedEvent(review, report.getId()))
                    .filter(java.util.Objects::nonNull).forEach(events::add);
        }
        if (events.isEmpty()) return List.of();
        Map<Long, Set<IssueIdentity>> identities = new LinkedHashMap<>();
        comparisons.findInput(report.getId()).ifPresent(snapshot -> addIdentities(identities, snapshot));
        return events.stream().flatMap(event -> event.sourceFindingIds().stream().map(id -> {
            Set<IssueIdentity> saved = identities.getOrDefault(id, Set.of());
            Set<Long> topicIds = saved.stream().map(IssueIdentity::topicId).collect(Collectors.toSet());
            if (topicIds.isEmpty()) {
                topicIds = event.sources().stream().filter(source -> source.findingId() == id)
                        .flatMap(source -> java.util.stream.Stream.concat(
                                source.topics().stream().map(topic -> topic.id()),
                                source.collectionTopics().stream().map(topic -> topic.topicId())))
                        .collect(Collectors.toSet());
                if (topicIds.isEmpty()) topicIds = event.topics().stream().map(topic -> topic.id()).collect(Collectors.toSet());
            }
            return new RejectedEvidence(id, saved, Set.copyOf(topicIds));
        })).toList();
    }

    private static Set<Long> matching(Collection<Long> candidates, Map<Long, Set<IssueIdentity>> identities,
                                      Long reportTopicId, List<RejectedEvidence> rejected) {
        if (rejected.isEmpty()) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : candidates) {
            Set<IssueIdentity> saved = identities.getOrDefault(id, Set.of());
            Set<Long> targetTopics = reportTopicId == null
                    ? saved.stream().map(IssueIdentity::topicId).collect(Collectors.toSet()) : Set.of(reportTopicId);
            if (rejected.stream().anyMatch(source -> source.matches(id, saved, targetTopics))) result.add(id);
        }
        return Set.copyOf(result);
    }

    private static void addIdentities(Map<Long, Set<IssueIdentity>> identities, ReportComparisonSnapshot snapshot) {
        if (snapshot == null) return;
        snapshot.issues().stream().filter(issue -> issue.side() != null
                && issue.side().issueId() > 0 && issue.side().topicId() > 0).forEach(issue ->
                identities.computeIfAbsent(issue.findingId(), ignored -> new LinkedHashSet<>())
                        .add(new IssueIdentity(issue.side().issueId(), issue.side().topicId())));
    }

    private static Map<Long, Set<IssueIdentity>> identities(NewsReport report) {
        Map<Long, Set<IssueIdentity>> identities = new LinkedHashMap<>();
        if (report.getWeeklyInput() != null) {
            report.getWeeklyInput().sources().forEach(source -> addIdentities(identities, source.evidenceSnapshot()));
        }
        return identities;
    }

    private static Period period(NewsReport report) {
        if (report.getReportScope() != ReportScope.WEEKLY || report.getReportDate() == null
                || report.getReportEndDate() == null || report.getReportEndDate().isBefore(report.getReportDate())) return null;
        return new Period(report.getReportDate(), report.getReportEndDate());
    }

    private record Period(LocalDate from, LocalDate through) { }
    private record IssueIdentity(long issueId, long topicId) { }
    private record RejectedEvidence(long findingId, Set<IssueIdentity> identities, Set<Long> topicIds) {
        boolean matches(long targetId, Set<IssueIdentity> targetIdentities, Set<Long> targetTopics) {
            if (!topicIds.isEmpty() && !targetTopics.isEmpty()
                    && topicIds.stream().noneMatch(targetTopics::contains)) return false;
            if (!identities.isEmpty() && !targetIdentities.isEmpty()) {
                return identities.stream().anyMatch(targetIdentities::contains);
            }
            // Legacy input can establish exact provenance, but never infer a replacement issue identity.
            return findingId == targetId;
        }
    }
}
