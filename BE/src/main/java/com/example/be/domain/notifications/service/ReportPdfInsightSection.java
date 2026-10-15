package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Selected-audience reading sections, bound to the same recipient-visible evidence as the email brief. */
final class ReportPdfInsightSection {
    private ReportPdfInsightSection() { }

    /**
     * Appends the selected audience's overview, issue explanations and follow-up checks.
     * Every included interpretation must be bound to grounded facts that remain visible to the recipient.
     * A null selection omits this section; an empty eligible selection prints the unavailable notice.
     * Scoring, conditional implications and the evidence appendix are outside the PDF reading scope.
     */
    static void write(ReportPdfLayout pdf, ReportInsightDTO.AudienceInsight insight,
                      ReportEventFeedbackProjection.View view) throws IOException {
        if (insight == null) return;
        Map<Long, Finding> visible = view.findings().stream()
                .filter(finding -> !view.excludedEvidenceFindingIds().contains(finding.getId()))
                .collect(Collectors.toMap(Finding::getId, Function.identity(), (first, duplicate) -> first));
        // Facts remain the authorization basis for interpretations, without a PDF evidence appendix.
        Set<String> allowed = new HashSet<>();
        for (var fact : list(insight.facts())) {
            Finding finding = visible.get(fact.findingId());
            if (finding != null && finding.getArticle() != null
                    && Objects.equals(finding.getArticle().getId(), fact.articleId())
                    && fact.articleId() != null && StringUtils.hasText(fact.id())
                    && StringUtils.hasText(fact.text()) && "grounded".equals(fact.groundedness())
                    && fact.evidenceSentenceIds() != null && !fact.evidenceSentenceIds().isEmpty()
                    && fact.evidenceSentenceIds().stream().allMatch(id -> id != null && id >= 0)) {
                allowed.add(fact.id());
            }
        }
        var overview = list(insight.overview()).stream()
                .filter(item -> supported(item.basisClaimIds(), allowed) && StringUtils.hasText(item.text())).toList();
        var issues = list(insight.issues()).stream()
                .filter(item -> visible.containsKey(item.findingId())
                        && supported(item.basisClaimIds(), allowed) && StringUtils.hasText(item.reason())).toList();
        var watches = list(insight.watchItems()).stream()
                .filter(item -> supported(item.basisClaimIds(), allowed) && StringUtils.hasText(item.topic())).toList();
        String heading = "리포트 관점 분석 · " + audience(insight.audience());
        // The aggregate headline and importance have no claim bindings and can repeat excluded evidence.
        if (overview.isEmpty() && issues.isEmpty() && watches.isEmpty()) {
            pdf.reserve(110);
            pdf.section(heading);
            pdf.muted(NotificationInsightBrief.AI_NOTICE);
            pdf.paragraph(NotificationInsightBrief.UNAVAILABLE);
            return;
        }
        List<ReportPdfLayout.ReadingGroup> groups = new ArrayList<>();
        if (!overview.isEmpty()) {
            groups.add(new ReportPdfLayout.ReadingGroup("리포트 종합 판단", overview.stream().map(item ->
                    new ReportPdfLayout.ReadingItem("해석", item.text(),
                            List.of(new ReportPdfLayout.ReadingField("해석의 조건", item.assumption())))).toList()));
        }
        if (!issues.isEmpty()) {
            groups.add(new ReportPdfLayout.ReadingGroup("먼저 살펴볼 이슈", issues.stream().map(item -> {
                Finding finding = visible.get(item.findingId());
                String title = finding.getArticle() == null ? null : finding.getArticle().getTitle();
                return new ReportPdfLayout.ReadingItem(StringUtils.hasText(title) ? "기사" : null, title,
                        List.of(new ReportPdfLayout.ReadingField("해석", item.reason())));
            }).toList()));
        }
        if (!watches.isEmpty()) {
            groups.add(new ReportPdfLayout.ReadingGroup("다음 확인 항목", watches.stream().map(item ->
                    new ReportPdfLayout.ReadingItem(null, item.topic(), List.of(
                            new ReportPdfLayout.ReadingField("확인할 지표", item.indicator()),
                            new ReportPdfLayout.ReadingField("판단 기준", item.trigger())))).toList()));
        }
        pdf.readingSection(heading, NotificationInsightBrief.AI_NOTICE, groups);
    }

    private static boolean supported(List<String> ids, Set<String> allowed) {
        return ids != null && !ids.isEmpty() && allowed.containsAll(ids);
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String audience(Audience audience) {
        return switch (audience) {
            case CHIP_MAKER -> "반도체 제조사";
            case EQUIPMENT_MAKER -> "장비·소재사";
            case MARKET_INVESTOR -> "시장·투자";
            case IT_INFRA -> "IT 인프라";
        };
    }

}
