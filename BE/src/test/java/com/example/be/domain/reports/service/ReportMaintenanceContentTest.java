package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.briefing.ReportBriefing;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportContent.ImportantEvent;
import com.example.be.domain.reports.entity.ReportStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReportMaintenanceContentTest {
    private final ImportantEvent first = event("피지컬 AI", 1L);
    private final ImportantEvent second = event("PDD Kit", 2L);
    private final ImportantEvent tokenCost = event("토큰값 반값인데 AI 청구서는 더 커졌다", 3L);
    private final ImportantEvent fourth = event("바이브코딩", 4L);
    private final ImportantEvent selected = event("리만 가설을 풀었다는 클로드 기사", 5L, 6L);

    @Test
    void promotesFifthSavedEventIntoThirdBriefingWithoutLosingAnyDetailedContent() {
        var original = saved();

        var result = ReportMaintenanceContent.promote(original, view(original), selected.title(), 2);

        assertEquals(List.of(first, second, selected, fourth, tokenCost), result.importantEvents());
        assertSame(selected, result.importantEvents().get(2), "saved wording and exact evidence must survive unchanged");
        assertSame(tokenCost, result.importantEvents().get(4), "removing a briefing must not delete its detailed event");
        assertEquals(List.of(first.summaryKo(), second.summaryKo(), selected.summaryKo()), result.executiveSummary());
        assertEquals(original.watchItems(), result.watchItems());
        assertEquals(original.sourceNotes(), result.sourceNotes());
        assertEquals(List.of(first.title(), second.title(), selected.title()),
                ReportBriefing.from(view(result)).items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(List.of(first, second, tokenCost, fourth, selected), original.importantEvents(),
                "the input snapshot remains immutable");
    }

    @Test
    void usesTheVisibleBriefingPositionWhenAnEarlierSavedEventIsTooLong() {
        var longDetail = new ImportantEvent("상세에서만 읽을 긴 사건", "가".repeat(181), "긴 내용도 보존", List.of(7L));
        var original = content(List.of(longDetail, first, second, tokenCost, fourth, selected));

        var result = ReportMaintenanceContent.promote(original, view(original), selected.title(), 2);

        assertSame(longDetail, result.importantEvents().getFirst());
        assertEquals(List.of(longDetail, first, second, selected, fourth, tokenCost), result.importantEvents());
        assertEquals(selected.title(), ReportBriefing.from(view(result)).items().get(2).title());
        assertTrue(result.importantEvents().contains(tokenCost));
    }

    @Test
    void repeatedPromotionKeepsTheSameSelectionAndAllDetails() {
        var once = ReportMaintenanceContent.promote(saved(), view(saved()), selected.title(), 2);

        var twice = ReportMaintenanceContent.promote(once, view(once), selected.title(), 2);

        assertEquals(once, twice);
    }

    @Test
    void refusesMissingOrAmbiguousTitlesInsteadOfGuessing() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportMaintenanceContent.promote(saved(), view(saved()), "리만", 2));
        var duplicate = new ImportantEvent(selected.title(), "별도 사건", "다른 근거", List.of(7L));
        var ambiguous = content(List.of(first, second, tokenCost, selected, duplicate));

        assertThrows(IllegalArgumentException.class,
                () -> ReportMaintenanceContent.promote(ambiguous, view(ambiguous), selected.title(), 2));
    }

    @Test
    void refusesAnEventHiddenByFeedbackOrMissingFromTheReadingView() {
        var original = saved();
        var feedbackView = view(original, Set.of(6L));
        assertThrows(IllegalArgumentException.class,
                () -> ReportMaintenanceContent.promote(original, feedbackView, selected.title(), 2));

        var visibleWithoutSelected = content(List.of(first, second, tokenCost, fourth));
        assertThrows(IllegalArgumentException.class,
                () -> ReportMaintenanceContent.promote(original, view(visibleWithoutSelected), selected.title(), 2));
    }

    @Test
    void refusesAnOverlongTargetWithoutTruncatingItsClaim() {
        var longTarget = new ImportantEvent(selected.title(), "나".repeat(181), selected.significance(),
                selected.sourceFindingIds());
        var original = content(List.of(first, second, tokenCost, fourth, longTarget));

        assertThrows(IllegalArgumentException.class,
                () -> ReportMaintenanceContent.promote(original, view(original), selected.title(), 2));
        assertEquals(181, original.importantEvents().getLast().summaryKo().length());
    }

    @Test
    void regenerationRetainsRequestedSavedEventAndOmittedTokenCostDetail() {
        var newEvent = event("새로 복구된 분석", 7L);
        var generatedContent = content(List.of(first, second, newEvent));
        var generated = document(generatedContent, List.of(1L, 2L, 7L), List.of(3L, 5L, 6L));

        var result = ReportMaintenanceContent.retainAndPromote(generated, saved(), view(saved()), selected.title(), 2);

        assertSame(selected, result.structuredContent().importantEvents().get(2));
        assertTrue(result.structuredContent().importantEvents().containsAll(List.of(tokenCost, fourth, newEvent)));
        assertEquals(List.of(first.summaryKo(), second.summaryKo(), selected.summaryKo()),
                result.structuredContent().executiveSummary());
        assertTrue(result.reflectedFindingIds().containsAll(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)));
        assertTrue(result.excludedFindingIds().isEmpty());
        assertEquals(ReportEventFeedbackProjection.markdown(result.structuredContent()), result.markdownBody());
        assertTrue(result.markdownBody().contains(tokenCost.summaryKo()));
        assertEquals(generated.modelName(), result.modelName());
        assertEquals(generated.promptVersion(), result.promptVersion());
        assertEquals(generated.inputTokens(), result.inputTokens());
        assertEquals(generated.outputTokens(), result.outputTokens());
        assertEquals(generated.costUsd(), result.costUsd());
        assertEquals(generated.credits(), result.credits());
        assertEquals(generatedContent.sourceNotes(), result.structuredContent().sourceNotes());
    }

    @Test
    void regenerationUsesExactSavedTargetInsteadOfARephrasedVersionOfItsEvidence() {
        var rephrased = new ImportantEvent("모델이 바꾼 제목", "모델이 바꾼 요약", "모델이 바꾼 중요도", List.of(5L, 6L));
        var generated = document(content(List.of(first, second, rephrased)), List.of(1L, 2L, 5L, 6L), List.of());

        var result = ReportMaintenanceContent.retainAndPromote(generated, saved(), view(saved()), selected.title(), 2);

        var events = result.structuredContent().importantEvents();
        assertSame(selected, events.get(2));
        assertFalse(events.contains(rephrased));
        assertEquals(1, events.stream().filter(event -> event.sourceFindingIds().contains(5L)).count());
        assertFalse(result.markdownBody().contains(rephrased.summaryKo()));
    }

    @Test
    void regenerationCannotReintroduceDisplacedStoryOrReplaceTheOtherSavedBriefings() {
        var rewrittenFirst = new ImportantEvent("다시 쓴 피지컬 AI", "변경된 요약", "", first.sourceFindingIds());
        var rewrittenSecond = new ImportantEvent("다시 쓴 PDD Kit", "변경된 요약", "", second.sourceFindingIds());
        var generated = document(content(List.of(tokenCost, rewrittenSecond, rewrittenFirst, fourth)),
                List.of(3L, 2L, 1L, 4L), List.of(5L, 6L));
        var alreadyEdited = ReportMaintenanceContent.promote(saved(), view(saved()), selected.title(), 2);

        // Both direct combined repair and regeneration after the initial editorial save must agree.
        for (var original : List.of(saved(), alreadyEdited)) {
            var result = ReportMaintenanceContent.retainAndPromote(generated, original, view(original), selected.title(), 2);
            var events = result.structuredContent().importantEvents();

            assertEquals(List.of(first, second, selected), events.subList(0, 3));
            assertSame(first, events.get(0));
            assertSame(second, events.get(1));
            assertSame(selected, events.get(2));
            assertTrue(events.indexOf(tokenCost) >= 3, "the displaced story remains only in the detailed events");
            assertTrue(events.contains(fourth));
            assertFalse(events.contains(rewrittenFirst));
            assertFalse(events.contains(rewrittenSecond));
            assertEquals(List.of(first.title(), second.title(), selected.title()),
                    ReportBriefing.from(view(result.structuredContent())).items().stream()
                            .map(ReportBriefing.Item::title).toList());
            assertEquals(List.of(first.summaryKo(), second.summaryKo(), selected.summaryKo()),
                    result.structuredContent().executiveSummary());
        }
    }

    @Test
    void regenerationPrefersSavedDetailWordingForMatchingEvidenceAndKeepsNewEvents() {
        var detail = new ImportantEvent("저장된 상세 제목", "저장된 상세 본문", "저장된 상세 의미", List.of(4L, 7L));
        var rewritten = new ImportantEvent("모델 상세 제목", "모델 상세 본문", "모델 상세 의미", List.of(7L, 4L));
        var unrelated = event("새로 확인된 사건", 7L);
        var original = content(List.of(first, second, tokenCost, detail, selected));
        var generated = document(content(List.of(first, second, tokenCost, rewritten, unrelated)),
                List.of(1L, 2L, 3L, 4L, 7L), List.of(5L, 6L));

        for (String promotion : Arrays.asList(null, selected.title())) {
            var result = ReportMaintenanceContent.retainAndPromote(generated, original, view(original), promotion, 2);
            var events = result.structuredContent().importantEvents();
            assertSame(detail, events.stream().filter(event -> event.title().equals(detail.title())).findFirst().orElseThrow());
            assertTrue(events.contains(unrelated));
            assertFalse(events.contains(rewritten));
            assertTrue(result.markdownBody().contains(detail.summaryKo()));
            assertFalse(result.markdownBody().contains(rewritten.summaryKo()));
            assertTrue(result.reflectedFindingIds().containsAll(List.of(4L, 7L)));
        }
    }

    @Test
    void regenerationWithoutNewPromotionKeepsTheAlreadyEditedBriefing() {
        var edited = ReportMaintenanceContent.promote(saved(), view(saved()), selected.title(), 2);
        var generated = document(content(List.of(tokenCost, second, first, fourth)),
                List.of(3L, 2L, 1L, 4L), List.of(5L, 6L));

        var result = ReportMaintenanceContent.retainAndPromote(generated, edited, view(edited), null, 0);

        assertEquals(edited.importantEvents(), result.structuredContent().importantEvents());
        assertEquals(List.of(first.title(), second.title(), selected.title()),
                ReportBriefing.from(view(result.structuredContent())).items().stream().map(ReportBriefing.Item::title).toList());
        assertEquals(List.of(first.summaryKo(), second.summaryKo(), selected.summaryKo()),
                result.structuredContent().executiveSummary());
    }

    @Test
    void regenerationPreservesDistinctSavedDetailsWithOverlappingOrIdenticalBriefingEvidence() {
        var sharesFirst = event("첫 브리핑과 일부 근거를 공유하는 별도 사건", 1L, 4L);
        var sharesSelected = event("선택 기사와 일부 근거를 공유하는 별도 사건", 4L, 5L);
        var sameEvidence = event("동일 근거의 별도 저장 사건", 1L);
        var original = content(List.of(first, second, tokenCost, sharesFirst, sharesSelected, sameEvidence, selected));
        var rewritten = new ImportantEvent("모델이 바꾼 선택 기사", "새 본문", "", List.of(6L, 5L));
        var newOverlap = event("일부 근거가 겹치는 신규 사건", 1L, 7L);
        var unrelated = event("다른 근거의 신규 사건", 7L);
        var generated = document(content(List.of(first, second, rewritten, newOverlap, unrelated)),
                List.of(1L, 2L, 5L, 6L, 7L), List.of(3L, 4L));

        var result = ReportMaintenanceContent.retainAndPromote(generated, original, view(original), selected.title(), 2);
        var events = result.structuredContent().importantEvents();

        assertEquals(List.of(first, second, selected), events.subList(0, 3));
        assertTrue(events.containsAll(original.importantEvents()));
        assertTrue(events.containsAll(List.of(newOverlap, unrelated)));
        assertEquals(original.importantEvents().size() + 2, events.size());
        assertFalse(events.contains(rewritten));
        assertTrue(result.reflectedFindingIds().containsAll(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)));
        assertTrue(result.excludedFindingIds().isEmpty());
    }

    @Test
    void regenerationWithoutPromotionOnlyRestoresSupportedVisibleBriefingEvents() {
        var longDetail = new ImportantEvent("긴 상세 사건", "가".repeat(181), "", List.of(7L));
        var hidden = event("읽기 화면에서 제외된 사건", 4L);
        var original = content(List.of(longDetail, hidden, first, second, tokenCost, selected));
        var visible = content(List.of(longDetail, first, second, tokenCost, selected));
        var generated = document(content(List.of(selected, tokenCost)), List.of(3L, 5L, 6L), List.of(1L, 2L, 4L));

        var result = ReportMaintenanceContent.retainAndPromote(generated, original, view(visible, Set.of(2L)), null, 0);
        var events = result.structuredContent().importantEvents();

        assertEquals(List.of(first, tokenCost, selected), events.subList(0, 3));
        assertTrue(events.contains(longDetail));
        assertFalse(events.contains(hidden));
        assertFalse(events.contains(second));
    }

    @Test
    void regenerationRejectsUnknownAndFeedbackExcludedEvidence() {
        var unknown = document(content(List.of(event("없는 근거", 99L))), List.of(99L), List.of());
        assertThrows(IllegalStateException.class,
                () -> ReportMaintenanceContent.retainAndPromote(unknown, saved(), view(saved()), selected.title(), 2));

        var excluded = document(content(List.of(first, second, tokenCost)), List.of(1L, 2L, 3L), List.of());
        assertThrows(IllegalStateException.class,
                () -> ReportMaintenanceContent.retainAndPromote(excluded, saved(), view(saved(), Set.of(3L)),
                        selected.title(), 2));
    }

    @Test
    void regenerationRejectsUnsupportedWatchAndCoverageReferencesEvenWhenEventsAreSupported() {
        var unknownWatch = new ReportContent(List.of(first.summaryKo()), List.of(first),
                List.of(new ReportContent.WatchItem("숨은 근거", "허용되지 않은 근거", List.of(99L))), List.of());
        var generated = document(unknownWatch, List.of(1L), List.of());
        assertThrows(IllegalStateException.class,
                () -> ReportMaintenanceContent.retainAndPromote(generated, saved(), view(saved()), null, 0));

        var unknownCoverage = document(content(List.of(first)), List.of(1L, 99L), List.of());
        assertThrows(IllegalStateException.class,
                () -> ReportMaintenanceContent.retainAndPromote(unknownCoverage, saved(), view(saved()), null, 0));
    }

    private ReportContent saved() {
        return content(List.of(first, second, tokenCost, fourth, selected));
    }

    private ReportContent content(List<ImportantEvent> events) {
        return new ReportContent(events.stream().limit(3).map(ImportantEvent::summaryKo).toList(), events,
                List.of(new ReportContent.WatchItem("저장된 관찰", "다음 발표 확인", List.of(7L))),
                List.of("저장된 수집 범위와 근거 한계"));
    }

    private ImportantEvent event(String title, Long... sourceIds) {
        return new ImportantEvent(title, title + "에 대한 저장된 요약", title + "의 저장된 의미", List.of(sourceIds));
    }

    private ReportEventFeedbackProjection.View view(ReportContent content) {
        return view(content, Set.of());
    }

    private ReportEventFeedbackProjection.View view(ReportContent content, Set<Long> excluded) {
        var findings = Arrays.stream(new Long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})
                .map(id -> Finding.builder().id(id).build()).toList();
        return new ReportEventFeedbackProjection.View(findings,
                new ReportReadingContent(ReportEventFeedbackProjection.markdown(content), content),
                !excluded.isEmpty(), excluded);
    }

    private ReportDocument document(ReportContent content, List<Long> reflected, List<Long> excluded) {
        return new ReportDocument("새 생성 제목", "새 생성 마크다운", "gpt-model", "prompt-v1", "openai",
                120L, 80L, new BigDecimal("0.01"), new BigDecimal("0.1"), ReportStatus.GENERATED,
                reflected, excluded, content);
    }
}
