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
        if (!brief.overview().isEmpty()) groups.add(new ReportPdfLayout.ReadingGroup("업무상 의미",
                brief.overview().stream().map(item -> new ReportPdfLayout.ReadingItem(null, item.text(), List.of())).toList()));
        if (!brief.watchItems().isEmpty()) groups.add(new ReportPdfLayout.ReadingGroup("다음 확인점",
                brief.watchItems().stream().map(item -> new ReportPdfLayout.ReadingItem(null, item.topic(), List.of(
                        new ReportPdfLayout.ReadingField("확인할 지표", item.indicator())))).toList()));
        if (!groups.isEmpty()) pdf.readingSection("관점 한눈에 · " + audience(insight.audience()),
                NotificationInsightBrief.AI_NOTICE, groups);
        else {
            pdf.section("관점 한눈에 · " + audience(insight.audience()));
            pdf.muted(brief.omittedItemCount() > 0 ? "전체 관점 분석은 보고서 화면에서 확인하세요."
                    : NotificationInsightBrief.UNAVAILABLE);
        }
    }

    private static String audience(Audience audience) {
        return NotificationBriefingSection.audience(audience);
    }
}
