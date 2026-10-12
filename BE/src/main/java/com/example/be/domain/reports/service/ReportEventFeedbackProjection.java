package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.feedback.model.FeedbackModels.Category;
import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.domain.feedback.model.FeedbackModels.Status;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Confirmed report reviews change reading and new delivery views, preserving stored reports and issued snapshots. */
@Service
@RequiredArgsConstructor
public class ReportEventFeedbackProjection {
    private final FeedbackStore store;
    private final ReportEventSnapshotFactory snapshots;
    private final DailyReportFeedbackExclusions dailyExclusions;

    public List<Feedback> reviews(long reportId) {
        return store.eventFeedback(reportId);
    }

    public Map<Long, List<Feedback>> reviewsByReport(Collection<Long> reportIds) {
        if (reportIds.isEmpty()) return Map.of();
        return store.eventReviewStates(reportIds).stream().collect(Collectors.groupingBy(Feedback::reportId));
    }

    public boolean hasConfirmedErrors(List<Feedback> reviews) {
        return reviews.stream().anyMatch(ReportEventFeedbackProjection::confirmedError);
    }

    public Map<Long, Set<Long>> inheritedFindingIdsByReport(Collection<NewsReport> reports) {
        return dailyExclusions.forReports(reports);
    }

    /** Capture before filtering so surviving events keep their original revision-bound identities. */
    public List<Item> events(NewsReport report, ReportFindings.Visible visible, List<Feedback> reviews) {
        Set<String> excluded = confirmedKeys(report.getId(), reviews);
        Set<Long> inherited = dailyExclusions.forReport(report);
        return snapshots.capture(report, visible).stream()
                .filter(item -> !excluded.contains(item.event().key()))
                .filter(item -> item.event().sourceFindingIds().stream().noneMatch(inherited::contains)).toList();
    }

    public View project(NewsReport report, ReportFindings.Visible visible, List<Feedback> reviews) {
        return project(report, visible, reviews, dailyExclusions.forReport(report));
    }

    /** List reads resolve inherited decisions in a batch and use the same projection as detail/delivery. */
    public View project(NewsReport report, ReportFindings.Visible visible, List<Feedback> reviews,
                        Set<Long> inherited) {
        ReportReadingContent original = ReportReadingContent.from(report, visible);
        Set<String> excluded = confirmedKeys(report.getId(), reviews);
        if (excluded.isEmpty() && inherited.isEmpty()) {
            return new View(visible.findings(), original, false);
        }
        if (original.structuredContent() == null) {
            var kept = visible.findings().stream().filter(f -> !inherited.contains(f.getId())).toList();
            if (kept.size() == visible.findings().size()) return new View(kept, original, false);
            return new View(kept, ReportReadingContent.from(report, new ReportFindings.Visible(kept, true)), true);
        }
        // Snapshot keys depend on the complete original report and must not be recaptured from this view.
        List<Item> rejected = excluded.isEmpty() ? List.of() : snapshots.capture(report, visible).stream()
                .filter(item -> excluded.contains(item.event().key())).toList();

        Set<Integer> rejectedIndexes = rejected.stream().map(item -> item.event().index()).collect(Collectors.toSet());
        ReportContent stored = original.structuredContent();
        java.util.stream.IntStream.range(0, stored.importantEvents().size())
                .filter(index -> stored.importantEvents().get(index).sourceFindingIds().stream().anyMatch(inherited::contains))
                .forEach(rejectedIndexes::add);
        boolean inheritedEvidence = visible.findings().stream().anyMatch(f -> inherited.contains(f.getId()))
                || stored.watchItems().stream().anyMatch(item -> item.sourceFindingIds().stream().anyMatch(inherited::contains));
        if (rejectedIndexes.isEmpty() && !inheritedEvidence) return new View(visible.findings(), original, false);
        List<ReportContent.ImportantEvent> events = java.util.stream.IntStream.range(0, stored.importantEvents().size())
                .filter(index -> !rejectedIndexes.contains(index)).mapToObj(stored.importantEvents()::get).toList();
        Set<Long> rejectedSources = rejectedIndexes.stream().map(stored.importantEvents()::get)
                .flatMap(event -> event.sourceFindingIds().stream())
                .collect(Collectors.toSet());
        rejectedSources.addAll(inherited);
        // A watch can repeat the rejected claim even when another valid event shares its evidence.
        var watches = stored.watchItems().stream()
                .filter(item -> !item.sourceFindingIds().isEmpty()
                        && item.sourceFindingIds().stream().noneMatch(rejectedSources::contains)).toList();
        Set<Long> retainedSources = events.stream().flatMap(event -> event.sourceFindingIds().stream())
                .collect(Collectors.toSet());
        watches.forEach(item -> retainedSources.addAll(item.sourceFindingIds()));
        Set<Long> hiddenFindings = new HashSet<>(rejectedSources);
        stored.watchItems().stream().filter(item -> !watches.contains(item))
                .forEach(item -> hiddenFindings.addAll(item.sourceFindingIds()));
        hiddenFindings.removeAll(retainedSources);
        List<Finding> findings = visible.findings().stream().filter(f -> !hiddenFindings.contains(f.getId())).toList();
        // Summary, markdown and notes lack per-sentence provenance. Rebuild them from surviving events.
        var content = new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                events, watches, List.of());
        return new View(findings, new ReportReadingContent(markdown(content), content), true);
    }

    private static Set<String> confirmedKeys(long reportId, List<Feedback> reviews) {
        return reviews.stream().filter(review -> review.reportId() == reportId)
                .filter(ReportEventFeedbackProjection::confirmedError).map(Feedback::eventKey).collect(Collectors.toSet());
    }

    static boolean confirmedError(Feedback review) {
        return review.capabilityId() == null && review.recipientId() == null && review.itemId() == null
                && review.eventKey() != null && review.category() != Category.PREFERENCE
                && review.status() == Status.COMPLETED && "CONFIRMED_ERROR".equals(review.verdict());
    }

    private static String markdown(ReportContent content) {
        StringBuilder body = new StringBuilder("## 핵심 요약\n\n");
        content.executiveSummary().forEach(summary -> body.append("- ").append(ReportMarkdown.text(summary)).append('\n'));
        body.append("\n## 중요 이벤트\n");
        content.importantEvents().forEach(event -> {
            body.append("\n### ").append(ReportMarkdown.text(event.title())).append("\n\n")
                    .append(ReportMarkdown.text(event.summaryKo())).append('\n');
            if (event.significance() != null && !event.significance().isBlank()) {
                body.append("\n").append(ReportMarkdown.text(event.significance())).append('\n');
            }
        });
        if (!content.watchItems().isEmpty()) {
            body.append("\n## 관찰 항목\n\n");
            content.watchItems().forEach(item -> body.append("- ").append(ReportMarkdown.text(item.topic())).append(": ")
                    .append(ReportMarkdown.text(item.reason())).append('\n'));
        }
        return body.toString();
    }

    public record View(List<Finding> findings, ReportReadingContent readingContent, boolean excludedEvents) {
        public View { findings = List.copyOf(findings); }

        /** Apply personal source decisions without recreating an event excluded by a local review. */
        public View suppress(NewsReport report, Set<Long> suppressedFindingIds) {
            List<Finding> kept = findings.stream().filter(f -> !suppressedFindingIds.contains(f.getId())).toList();
            if (kept.size() == findings.size()) return this;
            if (!excludedEvents || readingContent.structuredContent() == null) {
                return new View(kept, ReportReadingContent.from(report, new ReportFindings.Visible(kept, true)), excludedEvents);
            }
            Set<Long> allowed = kept.stream().map(Finding::getId).collect(Collectors.toSet());
            ReportContent current = readingContent.structuredContent();
            var events = current.importantEvents().stream()
                    .filter(event -> !event.sourceFindingIds().isEmpty() && allowed.containsAll(event.sourceFindingIds())).toList();
            Set<Long> droppedSources = current.importantEvents().stream()
                    .filter(event -> !events.contains(event)).flatMap(event -> event.sourceFindingIds().stream())
                    .collect(Collectors.toSet());
            var watches = current.watchItems().stream()
                    .filter(item -> !item.sourceFindingIds().isEmpty() && allowed.containsAll(item.sourceFindingIds())
                            && item.sourceFindingIds().stream().noneMatch(droppedSources::contains)).toList();
            current.watchItems().stream().filter(item -> !watches.contains(item))
                    .forEach(item -> droppedSources.addAll(item.sourceFindingIds()));
            Set<Long> retainedSources = events.stream().flatMap(event -> event.sourceFindingIds().stream())
                    .collect(Collectors.toSet());
            watches.forEach(item -> retainedSources.addAll(item.sourceFindingIds()));
            droppedSources.removeAll(retainedSources);
            kept = kept.stream().filter(finding -> !droppedSources.contains(finding.getId())).toList();
            var content = new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                    events, watches, List.of());
            return new View(kept, new ReportReadingContent(markdown(content), content), true);
        }
    }
}
