package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.insight.ReportInsightBriefing;
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

    static void brief(ReportPdfLayout pdf, ReportInsightDTO.AudienceInsight insight,
                      ReportEventFeedbackProjection.View view) throws IOException {
        if (insight == null) return;
        var brief = ReportInsightBriefing.from(insight, view.findings(), view.excludedEvidenceFindingIds());
        List<ReportPdfLayout.ReadingGroup> groups = new ArrayList<>();
        if (!brief.facts().isEmpty()) groups.add(new ReportPdfLayout.ReadingGroup("원문에서 확인한 내용",
                brief.facts().stream().map(fact -> new ReportPdfLayout.ReadingItem(
                        switch (fact.claimType()) {
                            case "FORECAST" -> "계획·전망";
                            case "OPINION" -> "의견·발언";
                            default -> "확인된 사실";
                        }, fact.text(), fact.attributedTo() == null ? List.of() :
                        List.of(new ReportPdfLayout.ReadingField("발화 주체", fact.attributedTo())))).toList()));
        if (!brief.overview().isEmpty()) groups.add(new ReportPdfLayout.ReadingGroup("업무상 의미",
                brief.overview().stream().map(item -> new ReportPdfLayout.ReadingItem(null, item.text(),
                        List.of(new ReportPdfLayout.ReadingField("해석의 조건", item.assumption())))).toList()));
        if (!brief.watchItems().isEmpty()) groups.add(new ReportPdfLayout.ReadingGroup("다음 확인점",
                brief.watchItems().stream().map(item -> new ReportPdfLayout.ReadingItem(null, item.topic(), List.of(
                        new ReportPdfLayout.ReadingField("확인할 지표", item.indicator()),
                        new ReportPdfLayout.ReadingField("판단 기준", item.trigger())))).toList()));
        if (!groups.isEmpty()) pdf.readingSection("관점 한눈에 · " + audience(insight.audience()),
                NotificationInsightBrief.AI_NOTICE, groups);
        else {
            pdf.section("관점 한눈에 · " + audience(insight.audience()));
            pdf.muted(brief.omittedItemCount() > 0 ? "긴 분석은 뒤쪽 관점 분석에서 확인하세요."
                    : NotificationInsightBrief.UNAVAILABLE);
        }
        if (brief.omittedItemCount() > 0)
            pdf.muted("간단히 표시하지 않은 " + brief.omittedItemCount() + "개 항목은 뒤쪽 관점 분석에서 확인하세요.");
    }

    /**
     * Appends the selected audience's overview and follow-up checks.
     * Every included interpretation must be bound to grounded facts that remain visible to the recipient.
     * A null selection omits this section; an empty eligible selection prints the unavailable notice.
     * Issue explanations, scoring, conditional implications and the evidence appendix are outside the PDF reading scope.
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
        var watches = list(insight.watchItems()).stream()
                .filter(item -> supported(item.basisClaimIds(), allowed) && StringUtils.hasText(item.topic())).toList();
        String heading = "리포트 관점 분석 · " + audience(insight.audience());
        // The aggregate headline and importance have no claim bindings and can repeat excluded evidence.
        if (overview.isEmpty() && watches.isEmpty()) {
            pdf.reserve(110);
            pdf.section(heading);
            pdf.muted(NotificationInsightBrief.AI_NOTICE);
            pdf.paragraph(NotificationInsightBrief.UNAVAILABLE);
            return;
        }
        List<ReportPdfLayout.ReadingGroup> groups = new ArrayList<>();
        if (!overview.isEmpty()) {
            groups.add(new ReportPdfLayout.ReadingGroup("리포트 종합 판단", overview.stream().map(item ->
                    new ReportPdfLayout.ReadingItem("해석", separateQuotedText(item.text()),
                            List.of(new ReportPdfLayout.ReadingField("해석의 조건", separateQuotedText(item.assumption()))))).toList()));
        }
        if (!watches.isEmpty()) {
            groups.add(new ReportPdfLayout.ReadingGroup("다음 확인 항목", watches.stream().map(item ->
                    new ReportPdfLayout.ReadingItem(null, separateQuotedText(item.topic()), List.of(
                            new ReportPdfLayout.ReadingField("확인할 지표", separateQuotedText(item.indicator())),
                            new ReportPdfLayout.ReadingField("판단 기준", separateQuotedText(item.trigger()))))).toList()));
        }
        pdf.readingSection(heading, NotificationInsightBrief.AI_NOTICE, groups);
    }

    /** Separate the generated source quotation from its prose without changing either text. */
    private static String separateQuotedText(String value) {
        String prefix = "원문: 「";
        if (value == null || !value.startsWith(prefix)) return value;
        int depth = 1;
        for (int index = prefix.length(); index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '「') depth++;
            if (character == '」' && --depth == 0) {
                String remainder = value.substring(index + 1);
                String prose = remainder.stripLeading();
                boolean labeled = List.of("해석:", "미확인 가정:", "반증 조건:", "관찰 항목:").stream()
                        .anyMatch(label -> prose.startsWith(label) && StringUtils.hasText(prose.substring(label.length())));
                String separator = remainder.substring(0, remainder.length() - prose.length());
                if (!labeled || separator.contains("\n") || separator.contains("\r")) return value;
                return value.substring(0, index + 1) + "\n\n" + prose;
            }
        }
        return value;
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
