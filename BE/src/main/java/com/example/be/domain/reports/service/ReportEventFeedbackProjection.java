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

/** Local report reviews change the read view, never stored reports or recipient delivery snapshots. */
@Service
@RequiredArgsConstructor
public class ReportEventFeedbackProjection {
    private final FeedbackStore store;
    private final ReportEventSnapshotFactory snapshots;

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

    /** Capture before filtering so surviving events keep their original revision-bound identities. */
    public List<Item> events(NewsReport report, ReportFindings.Visible visible, List<Feedback> reviews) {
        Set<String> excluded = confirmedKeys(report.getId(), reviews);
        return snapshots.capture(report, visible).stream()
                .filter(item -> !excluded.contains(item.event().key())).toList();
    }

    public View project(NewsReport report, ReportFindings.Visible visible, List<Feedback> reviews) {
        ReportReadingContent original = ReportReadingContent.from(report, visible);
        Set<String> excluded = confirmedKeys(report.getId(), reviews);
        if (excluded.isEmpty() || original.structuredContent() == null) {
            return new View(visible.findings(), original, false);
        }
        // Snapshot keys depend on the complete original report and must not be recaptured from this view.
        List<Item> rejected = snapshots.capture(report, visible).stream()
                .filter(item -> excluded.contains(item.event().key())).toList();
        if (rejected.isEmpty()) return new View(visible.findings(), original, false);

        Set<Integer> rejectedIndexes = rejected.stream().map(item -> item.event().index()).collect(Collectors.toSet());
        ReportContent stored = original.structuredContent();
        List<ReportContent.ImportantEvent> events = java.util.stream.IntStream.range(0, stored.importantEvents().size())
                .filter(index -> !rejectedIndexes.contains(index)).mapToObj(stored.importantEvents()::get).toList();
        if (events.size() == stored.importantEvents().size()) return new View(visible.findings(), original, false);
        Set<Long> rejectedSources = rejected.stream().flatMap(item -> item.event().sourceFindingIds().stream())
                .collect(Collectors.toSet());
        Set<Long> retainedSources = events.stream().flatMap(event -> event.sourceFindingIds().stream())
                .collect(Collectors.toSet());
        Set<Long> hiddenFindings = new HashSet<>(rejectedSources);
        hiddenFindings.removeAll(retainedSources);
        List<Finding> findings = visible.findings().stream().filter(f -> !hiddenFindings.contains(f.getId())).toList();
        // A watch can repeat the rejected claim even when another valid event shares its evidence.
        var watches = stored.watchItems().stream()
                .filter(item -> !item.sourceFindingIds().isEmpty()
                        && item.sourceFindingIds().stream().noneMatch(rejectedSources::contains)).toList();
        // Summary, markdown and notes lack per-sentence provenance. Rebuild them from surviving events.
        var content = new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                events, watches, List.of());
        return new View(findings, new ReportReadingContent(markdown(content), content), true);
    }

    private static Set<String> confirmedKeys(long reportId, List<Feedback> reviews) {
        return reviews.stream().filter(review -> review.reportId() == reportId)
                .filter(ReportEventFeedbackProjection::confirmedError).map(Feedback::eventKey).collect(Collectors.toSet());
    }

    private static boolean confirmedError(Feedback review) {
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
    }
}
