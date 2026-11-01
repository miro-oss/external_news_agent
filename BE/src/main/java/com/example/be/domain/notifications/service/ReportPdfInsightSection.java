package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Selected-audience reading sections, bound to the same recipient-visible evidence as the email brief. */
final class ReportPdfInsightSection {
    private ReportPdfInsightSection() { }

    static void brief(ReportPdfLayout pdf, ReportInsightDTO.AudienceInsight insight,
                      ReportEventFeedbackProjection.View view) throws IOException {
        if (insight == null) return;
        var brief = NotificationBriefingSection.insightBrief(insight, view);
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
            pdf.muted(brief.omittedItemCount() > 0 ? "전체 관점 분석은 보고서 화면에서 확인하세요."
                    : NotificationInsightBrief.UNAVAILABLE);
        }
        if (!groups.isEmpty() && brief.omittedItemCount() > 0)
            pdf.muted("전체 관점 분석은 보고서 화면에서 확인하세요.");
    }

    private static String audience(Audience audience) {
        return NotificationBriefingSection.audience(audience);
    }
}
