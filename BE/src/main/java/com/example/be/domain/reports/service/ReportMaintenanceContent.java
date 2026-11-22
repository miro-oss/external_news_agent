package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.briefing.ReportBriefing;
import com.example.be.domain.reports.entity.ReportContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Maintenance rearranges complete saved events; it never shortens or invents their claims. */
final class ReportMaintenanceContent {
    private ReportMaintenanceContent() { }

    static ReportContent promote(ReportContent stored, ReportEventFeedbackProjection.View view,
                                 String exactTitle, int briefingIndex) {
        if (stored == null) throw new IllegalArgumentException("구조화된 보고서 내용이 없습니다.");
        if (briefingIndex < 0 || briefingIndex > 2) throw new IllegalArgumentException("브리핑 위치는 0~2여야 합니다.");
        var matching = stored.importantEvents().stream().filter(event -> event.title().equals(exactTitle)).toList();
        if (matching.size() != 1) throw new IllegalArgumentException("정확한 제목으로 중요 이벤트 한 개를 지정해야 합니다.");
        var selected = matching.getFirst();
        var visible = view.readingContent().structuredContent();
        Set<Long> allowed = allowed(view);
        if (visible == null || !visible.importantEvents().contains(selected)
                || !supported(selected.sourceFindingIds(), allowed) || !fits(selected))
            throw new IllegalArgumentException("선택한 이벤트는 현재 핵심 브리핑에 표시할 수 없습니다.");
        var briefing = ReportBriefing.from(view).items();
        if (briefingIndex >= briefing.size()) throw new IllegalArgumentException("교체할 핵심 브리핑 항목이 없습니다.");
        var current = briefing.get(briefingIndex);
        var events = new ArrayList<>(stored.importantEvents());
        int destination = -1;
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (event.title().equals(current.title()) && event.sourceFindingIds().equals(current.sourceFindingIds())) {
                destination = index;
                break;
            }
        }
        if (destination < 0) throw new IllegalArgumentException("관찰 항목 대신 기존 중요 이벤트 위치를 지정해야 합니다.");
        Collections.swap(events, events.indexOf(selected), destination);
        return new ReportContent(events.stream().filter(visible.importantEvents()::contains)
                .filter(event -> supported(event.sourceFindingIds(), allowed) && fits(event))
                .limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                events, stored.watchItems(), stored.sourceNotes());
    }

    static ReportDocument retainAndPromote(ReportDocument generated, ReportContent original,
                                           ReportEventFeedbackProjection.View originalView,
                                           String exactTitle, int briefingIndex) {
        if (generated.structuredContent() == null) throw new IllegalStateException("생성 결과에 구조화된 내용이 없습니다.");
        var content = generated.structuredContent();
        Set<Long> allowed = allowed(originalView);
        if (content.importantEvents().stream().anyMatch(event -> !supported(event.sourceFindingIds(), allowed))
                || content.watchItems().stream().anyMatch(item -> !supported(item.sourceFindingIds(), allowed))
                || !allowed.containsAll(generated.reflectedFindingIds()))
            throw new IllegalStateException("생성 결과가 현재 허용된 보고서 근거를 벗어났습니다.");
        var events = new ArrayList<>(content.importantEvents());
        if (original != null) {
            var visible = originalView.readingContent().structuredContent();
            for (var saved : original.importantEvents()) {
                if (visible == null || !visible.importantEvents().contains(saved)
                        || !supported(saved.sourceFindingIds(), allowed)) continue;
                boolean explicitlySelected = exactTitle != null && saved.title().equals(exactTitle);
                if (explicitlySelected) {
                    // Keep the specifically requested saved claim, even if the model rephrased its source.
                    events.removeIf(event -> event.title().equals(exactTitle)
                            || !Collections.disjoint(event.sourceFindingIds(), saved.sourceFindingIds()));
                    events.add(saved);
                } else if (events.stream().noneMatch(event -> Set.copyOf(event.sourceFindingIds())
                        .equals(Set.copyOf(saved.sourceFindingIds())))) events.add(saved);
            }
        }
        var watches = new ArrayList<>(content.watchItems());
        if (original != null) for (var saved : original.watchItems()) {
            if (supported(saved.sourceFindingIds(), allowed) && !watches.contains(saved)) watches.add(saved);
        }
        var merged = rebuild(new ReportContent(content.executiveSummary(), content.importantEvents(), watches,
                content.sourceNotes()), events);
        if (exactTitle != null) {
            // Regeneration must retain the user's entire edited briefing, not just the promoted
            // slot: a model ranking could otherwise put the displaced story back in slots 1 or 2.
            var edited = promote(original, originalView, exactTitle, briefingIndex);
            var visible = originalView.readingContent().structuredContent().importantEvents();
            var requestedBriefing = edited.importantEvents().stream().filter(visible::contains)
                    .filter(event -> supported(event.sourceFindingIds(), allowed) && fits(event)).limit(3).toList();
            events.removeIf(event -> requestedBriefing.stream().anyMatch(saved ->
                    event.title().equals(saved.title())
                            || !Collections.disjoint(event.sourceFindingIds(), saved.sourceFindingIds())));
            events.addAll(0, requestedBriefing);
            merged = rebuild(merged, events);
        }
        var reflected = new java.util.LinkedHashSet<>(generated.reflectedFindingIds());
        merged.importantEvents().forEach(event -> reflected.addAll(event.sourceFindingIds()));
        merged.watchItems().forEach(item -> reflected.addAll(item.sourceFindingIds()));
        var excluded = generated.excludedFindingIds().stream().filter(id -> !reflected.contains(id)).toList();
        return new ReportDocument(generated.title(), ReportEventFeedbackProjection.markdown(merged),
                generated.modelName(), generated.promptVersion(), generated.llmProvider(), generated.inputTokens(),
                generated.outputTokens(), generated.costUsd(), generated.credits(), generated.status(),
                List.copyOf(reflected), excluded, merged);
    }

    static Set<Long> allowed(ReportEventFeedbackProjection.View view) {
        return view.findings().stream().map(Finding::getId)
                .filter(id -> id != null && !view.excludedEvidenceFindingIds().contains(id)).collect(Collectors.toSet());
    }

    private static boolean supported(List<Long> ids, Set<Long> allowed) {
        return !ids.isEmpty() && allowed.containsAll(ids);
    }

    private static boolean fits(ReportContent.ImportantEvent event) {
        return ReportBriefing.fits(event.title(), 100) && ReportBriefing.fits(event.summaryKo(), 180)
                && ReportBriefing.optionalFits(ReportBriefing.readingSignificance(event.significance()), 180);
    }

    private static ReportContent rebuild(ReportContent content, List<ReportContent.ImportantEvent> events) {
        return new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                events, content.watchItems(), content.sourceNotes());
    }
}
