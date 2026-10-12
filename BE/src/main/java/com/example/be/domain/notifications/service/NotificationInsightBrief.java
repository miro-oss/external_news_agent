package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bound every interpretation to the final recipient-visible evidence; headline has no claim bindings. */
record NotificationInsightBrief(String label, List<Item> items) {
    static final String UNAVAILABLE = "이 관점에서 전달할 수 있는 인사이트가 없습니다.";
    static final String AI_NOTICE = "AI 해석 · 원문 사실과 구분해 확인하세요.";

    static NotificationInsightBrief from(ReportInsightDTO.AudienceInsight insight, List<Finding> findings) {
        return from(insight, findings, Set.of());
    }

    static NotificationInsightBrief from(ReportInsightDTO.AudienceInsight insight, List<Finding> findings,
                                          Set<Long> excludedEvidenceFindingIds) {
        if (insight == null) return null;
        Map<Long, Finding> visible = findings.stream()
                // An allowed event can share a source with an excluded event. Saved interpretations
                // bind to findings, not events, so that shared source cannot validate an interpretation.
                .filter(finding -> !excludedEvidenceFindingIds.contains(finding.getId()))
                .collect(Collectors.toMap(Finding::getId, Function.identity(), (first, duplicate) -> first));
        Set<String> allowedClaims = new HashSet<>();
        for (var fact : insight.facts()) {
            Finding finding = visible.get(fact.findingId());
            if (finding != null && finding.getArticle() != null
                    && finding.getArticle().getId().equals(fact.articleId())
                    && "grounded".equals(fact.groundedness()) && !fact.evidenceSentenceIds().isEmpty()) {
                allowedClaims.add(fact.id());
            }
        }
        List<Item> items = insight.overview().stream()
                .filter(item -> supported(item.basisClaimIds(), allowedClaims) && StringUtils.hasText(item.text()))
                .limit(2).map(item -> new Item(item.text(), item.assumption())).toList();
        // Fallback only when the saved overview is empty, never replace filtered-out interpretation.
        if (insight.overview().isEmpty()) {
            items = insight.issues().stream()
                    .filter(item -> visible.containsKey(item.findingId())
                            && supported(item.basisClaimIds(), allowedClaims) && StringUtils.hasText(item.reason()))
                    .limit(2).map(item -> new Item(item.reason(), null)).toList();
        }
        String audience = switch (insight.audience()) {
            case CHIP_MAKER -> "반도체 제조사";
            case EQUIPMENT_MAKER -> "장비·소재사";
            case MARKET_INVESTOR -> "시장·투자";
            case IT_INFRA -> "IT 인프라";
        };
        return new NotificationInsightBrief("관점 인사이트 · " + audience, items);
    }

    private static boolean supported(List<String> ids, Set<String> allowed) {
        return ids != null && !ids.isEmpty() && allowed.containsAll(ids);
    }

    record Item(String text, String assumption) { }
}
