package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.stream.Collectors;

/** A static chart that remains readable offline and with images disabled in mail clients. */
final class NotificationKeywordSection {
    private NotificationKeywordSection() { }

    static final String TITLE = "키워드로 보는 이슈";
    static final String BASIS = "상위 선정 전 유효 이슈 기준 · 기사 반복 횟수가 아닙니다.";
    static final String NOTE = "같은 이슈에 여러 키워드가 포함되어 비중 합이 100%를 넘을 수 있습니다.";
    static final String MATCH = "당시 등록한 포함 키워드를 원문 제목·본문에서 확인했습니다. 함께 나온 단어는 같은 이슈에서 확인한 등록 키워드입니다.";

    static void email(StringBuilder html, ReportKeywordDTO.View stats) {
        if (stats == null) return;
        html.append("<table role=\"presentation\" width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"table-layout:fixed;border-collapse:collapse;margin-top:20px\"><tbody><tr><td class=\"email-card\" "
                + "bgcolor=\"#f8fafc\" style=\"padding:24px;border:1px solid #dde3eb;background:#f8fafc;border-radius:12px\">"
                + "<h2 style=\"margin:0 0 10px;color:#191f28;font-size:18px\">").append(TITLE).append("</h2>");
        if (stats.status() != ReportKeywordDTO.Status.AVAILABLE) {
            note(html, unavailable(stats.unavailableReason()));
        } else {
            note(html, "유효 이슈 " + stats.denominatorIssueCount() + "건 · " + stats.periodLabel());
            note(html, BASIS);
            if (stats.items().isEmpty()) note(html, "등록된 포함 키워드가 없습니다.");
            else if (stats.denominatorIssueCount() == 0) note(html, "집계할 유효 이슈가 없어 비중을 계산하지 않았습니다.");
            for (var item : stats.items()) {
                html.append("<p style=\"margin:16px 0 3px;font-size:14px;line-height:1.6;color:#191f28;overflow-wrap:anywhere;word-break:break-word\"><strong>")
                        .append(escape(item.keyword())).append("</strong><br><span style=\"color:#4e5968;font-size:12px\">")
                        .append(escape(amount(stats, item))).append("</span></p>");
                // A fixed 0–100 scale: zero has no colored mark and a missing share is never shown as 0%.
                html.append("<table role=\"presentation\" aria-hidden=\"true\" width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"border-collapse:collapse;table-layout:fixed\"><tbody><tr>");
                double share = bounded(item.sharePercent());
                if (share > 0) html.append("<td width=\"").append(number(share)).append("%\" height=\"8\" bgcolor=\"#1b64da\" "
                        + "style=\"height:8px;background:#1b64da;font-size:0;line-height:0\"></td>");
                if (share < 100) html.append("<td width=\"").append(number(100 - share)).append("%\" height=\"8\" bgcolor=\"#e2e8f0\" "
                        + "style=\"height:8px;background:#e2e8f0;font-size:0;line-height:0\"></td>");
                html.append("</tr></tbody></table>");
                if (!item.relatedKeywords().isEmpty()) note(html, related(item));
            }
            if (stats.omittedKeywordCount() > 0) note(html, "관련 이슈 수가 많은 키워드부터 " + stats.items().size()
                    + "개를 표시했습니다. 나머지 등록 키워드 " + stats.omittedKeywordCount() + "개는 생략했습니다.");
            if (!stats.items().isEmpty()) { note(html, NOTE); note(html, MATCH); }
        }
        html.append("</td></tr></tbody></table>");
    }

    static void pdf(ReportPdfLayout pdf, ReportKeywordDTO.View stats) throws IOException {
        if (stats == null) return;
        pdf.reserve(180);
        pdf.section(TITLE);
        if (stats.status() != ReportKeywordDTO.Status.AVAILABLE) { pdf.muted(unavailable(stats.unavailableReason())); return; }
        pdf.muted("유효 이슈 " + stats.denominatorIssueCount() + "건 · " + stats.periodLabel());
        pdf.muted(BASIS);
        if (stats.items().isEmpty()) pdf.muted("등록된 포함 키워드가 없습니다.");
        else if (stats.denominatorIssueCount() == 0) pdf.muted("집계할 유효 이슈가 없어 비중을 계산하지 않았습니다.");
        for (var item : stats.items()) pdf.keywordBar(item.keyword(), amount(stats, item), bounded(item.sharePercent()),
                item.relatedKeywords().isEmpty() ? "" : related(item));
        if (stats.omittedKeywordCount() > 0) pdf.muted("관련 이슈 수가 많은 키워드부터 " + stats.items().size()
                + "개 표시 · 나머지 등록 키워드 " + stats.omittedKeywordCount() + "개 생략");
        if (!stats.items().isEmpty()) { pdf.muted(NOTE); pdf.muted(MATCH); }
    }

    static String unavailable(String reason) {
        if (reason == null) return "키워드 집계 자료가 없습니다.";
        return switch (reason) {
            case "NO_SNAPSHOT" -> "이 보고서는 생성 당시 키워드 집계 자료가 없습니다.";
            case "INCOMPLETE_SOURCE" -> "주간 입력 자료가 완전하지 않아 키워드 비중을 계산하지 않았습니다.";
            case "MISSING_COLLECTION_CONTEXT" -> "당시 등록 키워드를 확인할 수 없어 집계를 제공하지 않습니다.";
            case "COMPANY_SCOPE_UNVERIFIED" -> "회사 제외 기준을 전체 후보에 확인할 수 없어 키워드 집계를 제공하지 않습니다.";
            case "PERSONALIZATION_UNVERIFIED" -> "개인 수신 기준을 전체 후보에 확인할 수 없어 키워드 집계를 제공하지 않습니다.";
            default -> "키워드 집계 근거를 확인할 수 없습니다.";
        };
    }

    private static String amount(ReportKeywordDTO.View stats, ReportKeywordDTO.Item item) {
        return item.issueCount() + "건 / 전체 " + stats.denominatorIssueCount() + "건 · "
                + (item.sharePercent() == null ? "비중 계산 안 함" : number(item.sharePercent()) + "%");
    }

    private static String related(ReportKeywordDTO.Item item) {
        return "함께 나온 키워드 · " + item.relatedKeywords().stream()
                .map(value -> value.keyword() + " " + value.issueCount() + "건").collect(Collectors.joining(" · "));
    }

    private static double bounded(Double value) { return value == null || !Double.isFinite(value) ? 0 : Math.max(0, Math.min(100, value)); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
    private static void note(StringBuilder html, String value) {
        html.append("<p style=\"margin:7px 0 0;color:#636c78;font-size:12px;line-height:1.7;overflow-wrap:anywhere;word-break:break-word\">")
                .append(escape(value)).append("</p>");
    }
}
