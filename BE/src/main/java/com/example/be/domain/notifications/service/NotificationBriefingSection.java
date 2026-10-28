package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.insight.ReportInsightBriefing;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import org.springframework.web.util.HtmlUtils;

/** Short, shared reading content. Prose is selected whole by the report projection, never clipped here. */
final class NotificationBriefingSection {
    private NotificationBriefingSection() { }

    static String audience(Audience audience) {
        return switch (audience) {
            case CHIP_MAKER -> "반도체 제조사";
            case EQUIPMENT_MAKER -> "장비·소재사";
            case MARKET_INVESTOR -> "시장·투자";
            case IT_INFRA -> "IT 인프라";
        };
    }

    static String unavailable(Audience audience) {
        return audience(audience) + " 관점 분석이 준비되지 않아 기본 보고서만 전달합니다.";
    }

    static void emailInsight(StringBuilder html, ReportInsightDTO.AudienceInsight insight,
                             ReportEventFeedbackProjection.View view) {
        if (insight == null) return;
        var brief = ReportInsightBriefing.from(insight, view.findings(), view.excludedEvidenceFindingIds());
        html.append("<section style=\"margin:24px 0\"><h2 style=\"margin:0 0 8px;color:#1b64da;font-size:16px\">")
                .append(escape("관점 인사이트 · " + audience(insight.audience())))
                .append("</h2><p style=\"color:#636c78;font-size:12px\">")
                .append(escape(NotificationInsightBrief.AI_NOTICE)).append("</p>");
        if (!brief.facts().isEmpty()) {
            label(html, "원문에서 확인한 내용");
            for (var fact : brief.facts()) {
                String type = switch (fact.claimType()) {
                    case "FORECAST" -> "계획·전망";
                    case "OPINION" -> "의견·발언";
                    default -> "확인된 사실";
                };
                field(html, type + (fact.attributedTo() == null ? "" : " · " + fact.attributedTo()), fact.text());
            }
        }
        if (!brief.overview().isEmpty()) {
            label(html, "업무상 의미");
            for (var item : brief.overview()) {
                paragraph(html, item.text());
                field(html, "해석의 조건", item.assumption());
            }
        }
        if (!brief.watchItems().isEmpty()) {
            label(html, "다음 확인점");
            for (var item : brief.watchItems()) {
                paragraph(html, item.topic());
                field(html, "확인할 지표", item.indicator());
                field(html, "판단 기준", item.trigger());
            }
        }
        if (brief.overview().isEmpty() && brief.watchItems().isEmpty() && brief.omittedItemCount() == 0)
            paragraph(html, NotificationInsightBrief.UNAVAILABLE);
        if (brief.omittedItemCount() > 0)
            field(html, "상세 안내", "긴 내용과 나머지 " + brief.omittedItemCount() + "개 항목은 첨부 PDF의 관점 분석에서 확인하세요.");
        html.append("</section>");
    }

    static void label(StringBuilder html, String value) {
        html.append("<h3 style=\"margin:20px 0 8px;color:#1b64da;font-size:13px\">").append(escape(value)).append("</h3>");
    }

    static void paragraph(StringBuilder html, String value) {
        html.append("<p style=\"margin:0 0 8px;color:#191f28;font-size:14px;line-height:1.8\">")
                .append(escape(value)).append("</p>");
    }

    static void field(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) return;
        html.append("<p style=\"margin:0 0 12px;color:#636c78;font-size:12px;line-height:1.7\"><strong>")
                .append(escape(label)).append("</strong> · ").append(escape(value)).append("</p>");
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8"); }
}
