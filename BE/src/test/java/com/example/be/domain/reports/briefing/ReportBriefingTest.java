package com.example.be.domain.reports.briefing;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ReportBriefingTest {
    @Test
    void marksPromotedWatchAsFollowupWhenOnlyItsReasonContainsTheUncertainty() {
        var content = new ReportContent(List.of(), List.of(), List.of(
                new ReportContent.WatchItem("계약 성사", "아직 성사 여부가 확인되지 않았습니다.", List.of(1L))), List.of());
        var view = view(content, Set.of(), 1L);
        var result = ReportBriefing.from(view);
        assertEquals("후속 확인 · 계약 성사", result.items().getFirst().title());
        assertEquals("아직 성사 여부가 확인되지 않았습니다.", result.items().getFirst().summary());
        assertEquals(List.of("후속 확인 · 계약 성사"), ReportBriefing.titles(view, 2));
        assertEquals("계약 성사", content.watchItems().getFirst().topic());
    }

    @Test
    void samePromotedTopicWithDifferentEvidenceDoesNotReappearAsAnotherWatch() {
        var watches = List.of(
                new ReportContent.WatchItem("같은 관찰", "첫 근거입니다.", List.of(1L)),
                new ReportContent.WatchItem("같은 관찰", "다른 근거입니다.", List.of(2L)),
                new ReportContent.WatchItem("별도 관찰", "다음 근거입니다.", List.of(3L)));
        var content = new ReportContent(List.of(), List.of(), watches, List.of());
        var view = view(content, Set.of(), 1L, 2L, 3L);
        var result = ReportBriefing.from(view);
        assertEquals(List.of("후속 확인 · 같은 관찰", "후속 확인 · 별도 관찰"),
                result.items().stream().map(ReportBriefing.Item::title).toList());
        assertTrue(result.watchItems().isEmpty());
        assertEquals(List.of("후속 확인 · 같은 관찰", "후속 확인 · 별도 관찰"), ReportBriefing.titles(view, 2));
        assertEquals(watches, content.watchItems());
    }

    @Test
    void followupLabelCountsTowardTitleLimitWithoutTruncatingStoredTopic() {
        String fitting = "😀".repeat(92);
        String tooLongWithLabel = "😀".repeat(93);
        var content = new ReportContent(List.of(), List.of(), List.of(
                new ReportContent.WatchItem(fitting, "확인할 항목입니다.", List.of(1L)),
                new ReportContent.WatchItem(tooLongWithLabel, "상세에 보존합니다.", List.of(2L))), List.of());
        var view = view(content, Set.of(), 1L, 2L);
        var result = ReportBriefing.from(view);
        assertEquals(List.of("후속 확인 · " + fitting), result.items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(100, result.items().getFirst().title().codePointCount(0, result.items().getFirst().title().length()));
        assertEquals(List.of(tooLongWithLabel), result.watchItems().stream().map(ReportBriefing.WatchItem::topic).toList());
        assertEquals(List.of("후속 확인 · " + fitting), ReportBriefing.titles(view, 2));
        assertEquals(tooLongWithLabel, content.watchItems().getLast().topic());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void fillsFeedbackGapsFromSurvivingWatchesWithoutRewritingStoredContent(int removed) {
        var events = java.util.stream.LongStream.rangeClosed(1, 3)
                .mapToObj(id -> event("기존 이슈 " + id, "확인된 변화입니다.", "", id)).toList();
        var watches = java.util.stream.LongStream.rangeClosed(4, 6)
                .mapToObj(id -> new ReportContent.WatchItem("관찰 " + id + " 예정",
                        "향후 시험을 계획했으며 완료한 것은 아닙니다.", List.of(id))).toList();
        var content = new ReportContent(List.of(), events, watches, List.of());
        Set<Long> excluded = java.util.stream.LongStream.rangeClosed(1, removed).boxed()
                .collect(java.util.stream.Collectors.toSet());
        var result = ReportBriefing.from(view(content, excluded, 1L, 2L, 3L, 4L, 5L, 6L));
        var expected = new java.util.ArrayList<String>();
        events.stream().skip(removed).map(ReportContent.ImportantEvent::title).forEach(expected::add);
        watches.stream().limit(removed).map(watch -> "후속 확인 · " + watch.topic()).forEach(expected::add);

        assertEquals(expected, result.items().stream().map(ReportBriefing.Item::title).toList());
        assertTrue(result.items().stream().flatMap(item -> item.sourceFindingIds().stream()).noneMatch(excluded::contains));
        assertEquals("향후 시험을 계획했으며 완료한 것은 아닙니다.", result.items().getLast().summary());
        assertNull(result.items().getLast().significance());
        assertEquals(watches.stream().skip(removed).limit(2).map(ReportContent.WatchItem::topic).toList(),
                result.watchItems().stream().map(ReportBriefing.WatchItem::topic).toList());
        assertEquals(events, content.importantEvents());
        assertEquals(watches, content.watchItems());
    }

    @Test
    void doesNotPromoteExcludedUnsupportedLongOrDuplicateEvidence() {
        var watches = List.of(
                new ReportContent.WatchItem("제외된 근거", "제외된 내용입니다.", List.of(1L)),
                new ReportContent.WatchItem("일부 없는 근거", "확인할 수 없습니다.", List.of(2L, 99L)),
                new ReportContent.WatchItem("가".repeat(101), "제목이 깁니다.", List.of(2L)),
                new ReportContent.WatchItem("긴 이유", "가".repeat(181), List.of(3L)),
                new ReportContent.WatchItem("계획 발표", "회사는 내년 시험을 계획했습니다.", List.of(4L)),
                new ReportContent.WatchItem("같은 근거 반복", "다른 제목으로 반복했습니다.", List.of(4L)),
                new ReportContent.WatchItem("일부 중복", "근거가 일부 겹칩니다.", List.of(4L, 5L)),
                new ReportContent.WatchItem("빈 근거", "근거가 없습니다.", List.of()),
                new ReportContent.WatchItem("둘째 확인", "확인할 항목입니다.", List.of(5L)),
                new ReportContent.WatchItem("셋째 확인", "확인할 항목입니다.", List.of(6L)),
                new ReportContent.WatchItem("남은 확인", "아래에 남겨 둡니다.", List.of(7L)));
        var content = new ReportContent(List.of(), List.of(event("제외 이슈", "제외했습니다.", "", 1L)), watches, List.of());
        var result = ReportBriefing.from(view(content, Set.of(1L), 1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertEquals(List.of("후속 확인 · 계획 발표", "후속 확인 · 둘째 확인", "후속 확인 · 셋째 확인"), result.items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(List.of("남은 확인"), result.watchItems().stream().map(ReportBriefing.WatchItem::topic).toList());
        assertEquals(2, result.omittedItemCount());
    }

    @Test
    void preservesMainOrderAndDoesNotPromoteAnotherWatchOfTheSameMainEvidence() {
        var content = new ReportContent(List.of(), List.of(event("첫 이슈", "확인했습니다.", "", 1L)), List.of(
                new ReportContent.WatchItem("같은 근거 후속", "후속 확인입니다.", List.of(1L)),
                new ReportContent.WatchItem("별도 근거 예정", "예정입니다.", List.of(2L))), List.of());
        var result = ReportBriefing.from(view(content, Set.of(), 1L, 2L));
        assertEquals(List.of("첫 이슈", "후속 확인 · 별도 근거 예정"), result.items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(List.of("같은 근거 후속"), result.watchItems().stream().map(ReportBriefing.WatchItem::topic).toList());
    }

    @Test
    void deliveryFillsOnlyItsOwnTwoSlotsAndStillKeepsLongMainSummaryTitles() {
        var content = new ReportContent(List.of(), List.of(event("짧은 이슈 제목", "가".repeat(181), "", 1L)), List.of(
                new ReportContent.WatchItem("첫 관찰 예정", "예정입니다.", List.of(2L)),
                new ReportContent.WatchItem("둘째 관찰", "확인할 항목입니다.", List.of(3L))), List.of());
        var view = view(content, Set.of(), 1L, 2L, 3L);
        assertEquals(List.of("짧은 이슈 제목", "후속 확인 · 첫 관찰 예정"), ReportBriefing.titles(view, 2));
        assertEquals(List.of("후속 확인 · 첫 관찰 예정", "후속 확인 · 둘째 관찰"), ReportBriefing.from(view).items().stream().map(ReportBriefing.Item::title).toList());
    }

    @Test
    void titleOnlyDeliveryKeepsSupportedWatchTitleEvenWhenItsReasonNeedsDetailSpace() {
        String reason = "가".repeat(181) + "시험은 예정이며 완료된 것은 아닙니다.";
        var content = new ReportContent(List.of(), List.of(), List.of(
                new ReportContent.WatchItem("후속 시험 예정", reason, List.of(1L))), List.of());
        var view = view(content, Set.of(), 1L);
        assertEquals(List.of("후속 확인 · 후속 시험 예정"), ReportBriefing.titles(view, 2));
        assertTrue(ReportBriefing.from(view).items().isEmpty());
        assertEquals(1, ReportBriefing.from(view).omittedItemCount());
        assertEquals(reason, content.watchItems().getFirst().reason());
    }

    @Test
    void selectsWholeSavedItemsInOrderAndCountsOnlyEligibleOmissions() {
        String longTail = "가".repeat(175) + "실행을 확정한 것은 아닙니다.";
        var content = new ReportContent(List.of("근거 연결 없는 문장은 브리핑에 사용하지 않는다."), List.of(
                event("길어서 상세에 유지", longTail, "", 1L),
                event("숨긴 복합 이슈", "숨긴 기사 포함", "", 1L, 2L),
                event("조건부 전망", "회사는 내년 양산을 예상하지만 확정하지 않았습니다.", null, 1L),
                event("두 번째", "본문 두 번째", "의미", 1L),
                event("세 번째", "본문 세 번째", "", 1L),
                event("네 번째", "본문 네 번째", "", 1L)),
                List.of(new ReportContent.WatchItem("확인점", "일정이 바뀌는 경우 재검토합니다.", List.of(1L))), List.of());
        var result = ReportBriefing.from(view(content, Set.of(2L), 1L, 2L));

        assertEquals(List.of("조건부 전망", "두 번째", "세 번째"), result.items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(2, result.omittedItemCount());
        assertEquals("회사는 내년 양산을 예상하지만 확정하지 않았습니다.", result.items().getFirst().summary());
        assertNull(result.items().getFirst().significance());
        assertEquals(1, result.watchItems().size());
        assertEquals(longTail, content.importantEvents().getFirst().summaryKo());
    }

    @Test
    void keepsUnicodeCodePointsWithoutSplittingAndBoundsWatchItems() {
        String title = "😀".repeat(100);
        var watches = List.of(
                new ReportContent.WatchItem("가".repeat(101), "상세에 남습니다.", List.of(1L)),
                new ReportContent.WatchItem("첫째", "확인합니다.", List.of(1L)),
                new ReportContent.WatchItem("둘째", "확인합니다.", List.of(1L)),
                new ReportContent.WatchItem("셋째", "확인합니다.", List.of(1L)),
                new ReportContent.WatchItem("알 수 없는 근거", "집계에서 제외합니다.", List.of(99L)));
        var result = ReportBriefing.from(view(new ReportContent(List.of(),
                List.of(event(title, "😀".repeat(180), "", 1L)), watches, List.of()), Set.of(), 1L));
        assertEquals(title, result.items().getFirst().title());
        assertEquals(180, result.items().getFirst().summary().codePointCount(0, 360));
        assertEquals(List.of("첫째", "둘째"), result.watchItems().stream().map(ReportBriefing.WatchItem::topic).toList());
        assertEquals(2, result.omittedItemCount());
    }

    @Test
    void legacyReportAndUnsupportedReferencesDoNotBecomeInventedBriefing() {
        assertEquals(ReportBriefing.empty(), ReportBriefing.from(view(null, Set.of(), 1L)));
        var content = new ReportContent(List.of("저장 요약"),
                List.of(event("근거 없음", "본문", "", 99L), event("빈 근거", "본문", "")), List.of(), List.of());
        assertEquals(ReportBriefing.empty(), ReportBriefing.from(view(content, Set.of(), 1L)));
    }

    private ReportContent.ImportantEvent event(String title, String summary, String significance, Long... refs) {
        return new ReportContent.ImportantEvent(title, summary, significance, List.of(refs));
    }

    private ReportEventFeedbackProjection.View view(ReportContent content, Set<Long> excluded, Long... ids) {
        return new ReportEventFeedbackProjection.View(List.of(ids).stream()
                .map(id -> Finding.builder().id(id).build()).toList(), new ReportReadingContent("원래 상세", content), false, excluded);
    }
}
