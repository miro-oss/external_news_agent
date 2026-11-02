package com.example.be.domain.reports.briefing;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A bounded reading view of saved, visible report items; never rewrites their claims. */
@Schema(description = "표시 가능한 저장 이슈 최대 3개·관찰 최대 2개. 이슈 빈자리는 제외되지 않은 별도 근거의 저장 관찰 항목으로 보충하고 제목에 후속 확인을 표시하며 전망·조건을 보존. 문장을 자르지 않으며 긴 항목은 상세에 보존")
public record ReportBriefing(String version, List<Item> items, List<WatchItem> watchItems,
                             int omittedItemCount) {
    public static final String VERSION = "report-briefing.v1";
    private static final String FOLLOW_UP_PREFIX = "후속 확인 · ";

    public ReportBriefing {
        items = List.copyOf(items);
        watchItems = List.copyOf(watchItems);
    }

    public static ReportBriefing empty() {
        return new ReportBriefing(VERSION, List.of(), List.of(), 0);
    }

    public static ReportBriefing from(ReportEventFeedbackProjection.View view) {
        return select(view, 3, false);
    }

    /** Delivery prints titles only, so a long saved event summary must not hide its short title. */
    public static List<String> titles(ReportEventFeedbackProjection.View view, int maximum) {
        return select(view, maximum, true).items().stream().map(Item::title).toList();
    }

    private static ReportBriefing select(ReportEventFeedbackProjection.View view, int maximum, boolean titlesOnly) {
        if (view == null || view.readingContent() == null || view.readingContent().structuredContent() == null) {
            return empty();
        }
        Set<Long> allowed = view.findings().stream().map(Finding::getId)
                .filter(id -> id != null && !view.excludedEvidenceFindingIds().contains(id))
                .collect(Collectors.toSet());
        var content = view.readingContent().structuredContent();
        List<Item> items = new ArrayList<>();
        List<WatchItem> watches = new ArrayList<>();
        Set<Long> representedSources = new HashSet<>();
        Set<Long> promotedSources = new HashSet<>();
        Set<String> promotedTopics = new HashSet<>();
        Set<String> titles = new HashSet<>();
        int omitted = 0;
        for (var event : content.importantEvents()) {
            String significance = readingSignificance(event.significance());
            if (!supported(event.sourceFindingIds(), allowed)) continue;
            representedSources.addAll(event.sourceFindingIds());
            if (titlesOnly && titles.contains(event.title())) continue;
            if (items.size() < maximum && fits(event.title(), 100) && (titlesOnly || (fits(event.summaryKo(), 180)
                    && optionalFits(significance, 180)))) {
                items.add(new Item(event.title(), event.summaryKo(), significance, event.sourceFindingIds()));
                titles.add(event.title());
            } else {
                omitted++;
            }
        }
        for (var watch : content.watchItems()) {
            if (!supported(watch.sourceFindingIds(), allowed)) continue;
            if (promotedTopics.contains(watch.topic())
                    || watch.sourceFindingIds().stream().anyMatch(promotedSources::contains)) continue;
            boolean fits = fits(watch.topic(), 100) && (titlesOnly || fits(watch.reason(), 180));
            String promotedTitle = FOLLOW_UP_PREFIX + watch.topic();
            if (items.size() < maximum && fits && fits(promotedTitle, 100)
                    && !titles.contains(watch.topic()) && !titles.contains(promotedTitle)
                    && watch.sourceFindingIds().stream().noneMatch(representedSources::contains)) {
                // Keep the saved wording, but label the follow-up even when only its title is delivered.
                items.add(new Item(promotedTitle, watch.reason(), null, watch.sourceFindingIds()));
                representedSources.addAll(watch.sourceFindingIds());
                promotedSources.addAll(watch.sourceFindingIds());
                promotedTopics.add(watch.topic());
                titles.add(promotedTitle);
                continue;
            }
            if (watches.size() < 2 && fits) {
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
