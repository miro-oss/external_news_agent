package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.WatchItem;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.briefing.ReportBriefing;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Schema(description = "저장된 관점 결과의 짧은 표시 모델. 원문 주장·업무 의미와 조건·다음 확인점을 각각 최대 2개 제공")
public record ReportInsightBriefing(String version, List<ReportInsightDTO.Fact> facts,
                                    List<Overview> overview, List<WatchItem> watchItems,
                                    int omittedItemCount) {
    public static final String VERSION = "report-insight-briefing.v1";

    public ReportInsightBriefing {
        facts = List.copyOf(facts);
        overview = List.copyOf(overview);
        watchItems = List.copyOf(watchItems);
    }

    public static ReportInsightBriefing empty() {
        return new ReportInsightBriefing(VERSION, List.of(), List.of(), List.of(), 0);
    }

    /** API results already carry facts assembled from the current, hash-matched input. */
    public static ReportInsightBriefing from(ReportInsightDTO.AudienceInsight insight) {
        return project(insight, validFacts(insight));
    }

    /** Reuse hash-validated facts only while their exact article/finding remains in the final delivery view. */
    public static ReportInsightBriefing from(ReportInsightDTO.AudienceInsight insight, List<Finding> findings,
                                             Set<Long> excludedFindingIds) {
        Map<Long, Finding> visible = new HashMap<>();
        Set<Long> ambiguous = new HashSet<>();
        for (var finding : list(findings)) {
            if (finding == null || finding.getId() == null || excludedFindingIds.contains(finding.getId())) continue;
            if (visible.putIfAbsent(finding.getId(), finding) != null) ambiguous.add(finding.getId());
        }
        ambiguous.forEach(visible::remove);
        var allowed = validFacts(insight);
        allowed.values().removeIf(fact -> !matchesSource(fact, visible.get(fact.findingId())));
        return project(insight, allowed);
    }

    private static ReportInsightBriefing project(ReportInsightDTO.AudienceInsight insight,
                                                 Map<String, ReportInsightDTO.Fact> allowed) {
        if (insight == null) return empty();
        List<Overview> overview = new ArrayList<>();
        List<WatchItem> watches = new ArrayList<>();
        List<Overview> candidates = list(insight.overview());
        boolean issueFallback = candidates.isEmpty();
        if (issueFallback) {
            candidates = list(insight.issues()).stream().filter(Objects::nonNull)
                    .filter(issue -> supported(issue.basisClaimIds(), allowed)
                            && issue.basisClaimIds().stream().allMatch(id ->
                                    Objects.equals(issue.findingId(), allowed.get(id).findingId())))
                    .map(issue -> new Overview(issue.reason(), issue.basisClaimIds(), null)).toList();
        }
        int omitted = 0;
        for (var item : candidates) {
            if (item == null || !supported(item.basisClaimIds(), allowed)) continue;
            String text = unquotedProse(item.text(), "해석");
            String assumption = unquotedProse(item.assumption(), "미확인 가정");
            if (overview.size() < 2 && ReportBriefing.fits(text, 180)
                    && ReportBriefing.optionalFits(assumption, 140)) {
                overview.add(new Overview(text, List.copyOf(item.basisClaimIds()), assumption));
            } else if (!issueFallback) {
                omitted++;
            }
        }
        for (var item : list(insight.watchItems())) {
            if (item == null || !supported(item.basisClaimIds(), allowed)) continue;
            String topic = unquotedProse(item.topic(), "관찰 항목");
            // Current agent uses 관찰 항목 for each field; older saved projections used these field labels.
            String indicator = unquotedProse(item.indicator(), Set.of("관찰 항목", "해석"));
            String trigger = unquotedProse(item.trigger(), Set.of("관찰 항목", "반증 조건"));
            if (watches.size() < 2 && ReportBriefing.fits(topic, 100)
                    && ReportBriefing.fits(indicator, 140) && ReportBriefing.fits(trigger, 140)) {
                watches.add(new WatchItem(topic, indicator, trigger, List.copyOf(item.basisClaimIds())));
            } else {
                omitted++;
            }
        }
        Set<String> cited = new HashSet<>();
        overview.forEach(item -> cited.addAll(item.basisClaimIds()));
        watches.forEach(item -> cited.addAll(item.basisClaimIds()));
        var facts = allowed.values().stream().filter(fact -> cited.contains(fact.id()))
                .filter(fact -> ReportBriefing.fits(fact.text(), 180)).limit(2).toList();
        return new ReportInsightBriefing(VERSION, facts, overview, watches, omitted);
    }

    private static LinkedHashMap<String, ReportInsightDTO.Fact> validFacts(ReportInsightDTO.AudienceInsight insight) {
        var result = new LinkedHashMap<String, ReportInsightDTO.Fact>();
        Set<String> ambiguous = new HashSet<>();
        if (insight == null) return result;
        for (var fact : list(insight.facts())) {
            if (fact == null || !validFact(fact)) continue;
            if (result.putIfAbsent(fact.id(), fact) != null) ambiguous.add(fact.id());
        }
        ambiguous.forEach(result::remove);
        return result;
    }

    private static boolean validFact(ReportInsightDTO.Fact fact) {
        return fact.findingId() != null && fact.findingId() > 0 && fact.articleId() != null && fact.articleId() > 0
                && claimIndex(fact) >= 0 && fact.text() != null && !fact.text().isBlank()
                && "grounded".equals(fact.groundedness())
                && Set.of("FACT", "FORECAST", "OPINION").contains(fact.claimType() == null ? "" : fact.claimType())
                && ("OPINION".equals(fact.claimType())
                    ? fact.attributedTo() != null && !fact.attributedTo().isBlank() : fact.attributedTo() == null)
                && fact.evidenceSentenceIds() != null && !fact.evidenceSentenceIds().isEmpty()
                && fact.evidenceSentenceIds().stream().allMatch(id -> id != null && id >= 0)
                && fact.evidenceSentenceIds().stream().distinct().count() == fact.evidenceSentenceIds().size();
    }

    private static int claimIndex(ReportInsightDTO.Fact fact) {
        String prefix = fact.findingId() + ":";
        if (fact.id() == null || !fact.id().startsWith(prefix)) return -1;
        String suffix = fact.id().substring(prefix.length());
        try {
            int index = Integer.parseInt(suffix);
            return index >= 0 && Integer.toString(index).equals(suffix) ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean matchesSource(ReportInsightDTO.Fact fact, Finding finding) {
        return finding != null && finding.getArticle() != null
                && Objects.equals(fact.articleId(), finding.getArticle().getId());
    }

    private static boolean supported(List<String> refs, Map<String, ReportInsightDTO.Fact> allowed) {
        return refs != null && !refs.isEmpty() && refs.stream().allMatch(id -> id != null && allowed.containsKey(id))
                && refs.stream().distinct().count() == refs.size();
    }

    /** Remove only a complete leading quotation with its exact field label, never a prose suffix. */
    public static String unquotedProse(String value, String label) {
        return unquotedProse(value, Set.of(label));
    }

    private static String unquotedProse(String value, Set<String> labels) {
        if (value == null) return null;
        String prose = value.endsWith("\\\r\n") ? value.substring(0, value.length() - 3)
                : value.endsWith("\\\n") ? value.substring(0, value.length() - 2) : value;
        String prefix = "원문: 「";
        if (!prose.startsWith(prefix)) return prose;
        int depth = 1;
        for (int index = prefix.length(); index < prose.length(); index++) {
            char valueAt = prose.charAt(index);
            if (valueAt == '「') depth++;
            if (valueAt != '」' || --depth != 0) continue;
            String tail = prose.substring(index + 1);
            for (String label : labels) {
                String marker = " " + label + ": ";
                if (index > prefix.length() && tail.startsWith(marker)
                        && !tail.substring(marker.length()).isBlank()) return tail.substring(marker.length());
            }
            return prose;
        }
        return prose;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }
}
