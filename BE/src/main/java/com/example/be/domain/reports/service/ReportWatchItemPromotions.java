package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportWatchItemKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/** Only moves saved, still-visible claims; never adds text or revives filtered evidence. */
final class ReportWatchItemPromotions {
    private ReportWatchItemPromotions() { }

    /** Move exact selected watches from the visible reading into events, preserving selection order and source text. */
    static ReportReadingContent apply(NewsReport report, ReportReadingContent reading) {
        var stored = report.getStructuredContent();
        var content = reading.structuredContent();
        if (stored == null || content == null || report.getPromotedWatchItemKeys().isEmpty()) return reading;
        var promoted = new HashSet<ReportContent.WatchItem>();
        var events = new ArrayList<>(content.importantEvents());
        for (String key : report.getPromotedWatchItemKeys()) {
            var watch = content.watchItems().stream().filter(item -> ReportWatchItemKey.of(item).equals(key)).findFirst().orElse(null);
            if (watch == null || !stored.watchItems().contains(watch) || !promoted.add(watch)) continue;
            if (events.stream().noneMatch(event -> matches(event, watch))) events.add(event(watch));
        }
        if (promoted.isEmpty()) return reading;
        var projected = new ReportContent(content.executiveSummary(), events,
                content.watchItems().stream().filter(item -> !promoted.contains(item)).toList(), content.sourceNotes());
        return new ReportReadingContent(ReportEventFeedbackProjection.markdown(projected), projected);
    }

    static ReportContent.ImportantEvent event(ReportContent.WatchItem watch) {
        return new ReportContent.ImportantEvent(watch.topic(), watch.reason(), null, watch.sourceFindingIds());
    }

    /** Match the complete saved claim and ordered evidence; significance is not part of a watch item. */
    static boolean matches(ReportContent.ImportantEvent event, ReportContent.WatchItem watch) {
        return Objects.equals(event.title(), watch.topic()) && Objects.equals(event.summaryKo(), watch.reason())
                && event.sourceFindingIds().equals(watch.sourceFindingIds());
    }
}
