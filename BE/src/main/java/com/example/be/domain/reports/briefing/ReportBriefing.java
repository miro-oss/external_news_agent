package com.example.be.domain.reports.briefing;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A bounded reading view of saved, visible report items; never rewrites their claims. */
@Schema(description = "표시 가능한 저장 이슈 최대 3개·관찰 최대 2개. 문장을 자르지 않으며 긴 항목은 상세에 보존")
public record ReportBriefing(String version, List<Item> items, List<WatchItem> watchItems,
                             int omittedItemCount) {
    public static final String VERSION = "report-briefing.v1";

    public ReportBriefing {
        items = List.copyOf(items);
        watchItems = List.copyOf(watchItems);
    }

    public static ReportBriefing empty() {
        return new ReportBriefing(VERSION, List.of(), List.of(), 0);
    }

    public static ReportBriefing from(ReportEventFeedbackProjection.View view) {
        if (view == null || view.readingContent() == null || view.readingContent().structuredContent() == null) {
            return empty();
        }
        Set<Long> allowed = view.findings().stream().map(Finding::getId)
                .filter(id -> id != null && !view.excludedEvidenceFindingIds().contains(id))
                .collect(Collectors.toSet());
        var content = view.readingContent().structuredContent();
        List<Item> items = new ArrayList<>();
        List<WatchItem> watches = new ArrayList<>();
        int omitted = 0;
        for (var event : content.importantEvents()) {
            String significance = readingSignificance(event.significance());
            if (!supported(event.sourceFindingIds(), allowed)) continue;
            if (items.size() < 3 && fits(event.title(), 100) && fits(event.summaryKo(), 180)
                    && optionalFits(significance, 180)) {
                items.add(new Item(event.title(), event.summaryKo(), significance, event.sourceFindingIds()));
            } else {
                omitted++;
            }
        }
        for (var watch : content.watchItems()) {
            if (!supported(watch.sourceFindingIds(), allowed)) continue;
            if (watches.size() < 2 && fits(watch.topic(), 100) && fits(watch.reason(), 180)) {
                watches.add(new WatchItem(watch.topic(), watch.reason(), watch.sourceFindingIds()));
            } else {
                omitted++;
            }
        }
        return new ReportBriefing(VERSION, items, watches, omitted);
    }

    private static boolean supported(List<Long> refs, Set<Long> allowed) {
        return refs != null && !refs.isEmpty() && refs.stream().allMatch(id -> id != null && allowed.contains(id));
    }

    /** Old weekly significance can be an internal dated evidence log rather than reading prose. */
    public static String readingSignificance(String value) {
        return value != null && java.util.regex.Pattern.compile(
                "\\b\\d{4}-\\d{2}-\\d{2}\\s+(?:주제별 분석 결과|일일 보고서):").matcher(value).find() ? "" : value;
    }

    public static boolean fits(String value, int maximum) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maximum;
    }

    public static boolean optionalFits(String value, int maximum) {
        return value == null || value.codePointCount(0, value.length()) <= maximum;
    }

    public record Item(String title, String summary, String significance, List<Long> sourceFindingIds) {
        public Item { sourceFindingIds = List.copyOf(sourceFindingIds); }
    }

    public record WatchItem(String topic, String reason, List<Long> sourceFindingIds) {
        public WatchItem { sourceFindingIds = List.copyOf(sourceFindingIds); }
    }
}
