package com.example.be.domain.reports.briefing;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportBriefingTest {
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
