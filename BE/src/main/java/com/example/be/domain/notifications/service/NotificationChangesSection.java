package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.comparison.ReportChanges;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** The same saved comparison is readable in mail and in its offline attachment. */
final class NotificationChangesSection {
    private NotificationChangesSection() { }
    private static final String TITLE = "지난 보고서와 달라진 점";
    private static final String BASIS = "새로 포함은 사건의 최초 발생을 뜻하지 않습니다. 수치는 수집한 유효 이슈 기준입니다.";

    static void email(StringBuilder html, ReportChanges changes) {
        if (changes == null) return;
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"table-layout:fixed;margin-top:20px\"><tbody><tr><td class=\"email-card\" bgcolor=\"#f8fafc\" style=\"padding:24px;border:1px solid #dde3eb;border-radius:12px;background:#f8fafc\"><h2 style=\"margin:0 0 10px;font-size:18px;color:#191f28\">")
                .append(TITLE).append("</h2>");
        note(html, periods(changes));
        if (changes.status() != ReportChanges.Status.READY) note(html, changes.message());
        else {
            note(html, counts(changes));
            if (changes.items().isEmpty()) note(html, "비교할 이슈 근거가 없습니다. 변화가 없다는 뜻은 아닙니다.");
            for (var item : briefItems(changes)) {
                html.append("<p style=\"margin:14px 0 4px;font-size:14px;line-height:1.6;color:#191f28;overflow-wrap:anywhere\"><strong>")
                        .append(escape(type(item.type()) + " · " + item.title())).append("</strong></p>");
                note(html, item.summary());
            }
            if (changes.items().size() > briefItems(changes).size()) note(html, "나머지 변화와 양쪽 근거는 첨부 PDF의 변화 비교 상세에서 확인하세요.");
            changes.notes().forEach(value -> note(html, value));
            note(html, BASIS);
        }
        var keywords = changes.keywordChanges();
        if (keywords != null) {
            html.append("<h3 style=\"margin:20px 0 8px;font-size:15px;color:#191f28\">키워드 변화</h3>");
            note(html, keywords.message());
            if (keywords.status() == ReportChanges.KeywordStatus.AVAILABLE) {
                note(html, denominator(keywords));
                note(html, "이전(회색) → 이번(파랑) · 비중 0~100% 동일 눈금");
                for (var item : keywords.items()) {
                    html.append("<p style=\"margin:12px 0 4px;font-size:13px;color:#191f28;overflow-wrap:anywhere\"><strong>")
                            .append(escape(item.keyword())).append("</strong></p>");
                    note(html, amount(item));
                    bar(html, item.previousSharePercent(), "#94a3b8");
                    bar(html, item.currentSharePercent(), "#1b64da");
                }
                if (keywords.lowSample()) note(html, "유효 이슈가 5건 미만인 기간이 있어 작은 변화에도 비중이 크게 달라질 수 있습니다.");
            }
        }
        html.append("</td></tr></tbody></table>");
    }

    static void pdfBrief(ReportPdfLayout pdf, ReportChanges changes) throws IOException {
        if (changes == null) return;
        pdf.reserve(140);
        pdf.section(TITLE);
        pdf.muted(periods(changes));
        if (changes.status() != ReportChanges.Status.READY) pdf.muted(changes.message());
        else {
            pdf.muted(counts(changes));
            if (changes.items().isEmpty()) pdf.muted("비교할 이슈 근거가 없습니다. 변화가 없다는 뜻은 아닙니다.");
            for (var item : briefItems(changes)) {
                pdf.reserve(85);
                pdf.subheading(type(item.type()) + " · " + item.title());
                pdf.paragraph(item.summary());
            }
            for (String note : changes.notes()) pdf.muted(note);
            pdf.muted(BASIS);
            if (!changes.items().isEmpty()) pdf.internalLink("변화 비교 상세와 양쪽 근거", "changes-detail");
        }
        var keywords = changes.keywordChanges();
        if (keywords == null) return;
        pdf.reserve(100);
        pdf.subheading("키워드 변화");
        pdf.muted(keywords.message());
        if (keywords.status() != ReportChanges.KeywordStatus.AVAILABLE) return;
        pdf.muted(denominator(keywords));
        pdf.muted("이전(회색) → 이번(파랑) · 비중 0~100% 동일 눈금");
        for (var item : keywords.items()) pdf.keywordComparison(item.keyword(), amount(item),
                bounded(item.previousSharePercent()), bounded(item.currentSharePercent()));
        if (keywords.lowSample()) pdf.muted("유효 이슈가 5건 미만인 기간이 있어 작은 변화에도 비중이 크게 달라질 수 있습니다.");
    }

    static void pdfDetails(ReportPdfLayout pdf, ReportChanges changes) throws IOException {
        if (changes == null || changes.status() != ReportChanges.Status.READY || changes.items().isEmpty()) return;
        pdf.pageBreak();
        pdf.anchor("changes-detail");
        pdf.section("변화 비교 상세");
        pdf.internalLink("핵심 브리핑으로 돌아가기", "summary");
        pdf.muted(periods(changes));
        for (var item : changes.items()) {
            pdf.reserve(100);
            pdf.subheading(type(item.type()) + " · " + item.title());
            pdf.paragraph(item.summary());
            side(pdf, "이전 보고서", item.previous());
            side(pdf, "이번 보고서", item.current());
        }
    }

    private static void side(ReportPdfLayout pdf, String label, ReportChanges.Side side) throws IOException {
        if (side == null) { pdf.muted(label + " · 연결된 근거 없음"); return; }
        pdf.reserve(75);
        pdf.keyValue(label, side.title());
        pdf.paragraph(side.summary());
        for (var claim : side.claims()) {
            pdf.bullet(claim.text());
            for (var evidence : claim.evidence()) {
                pdf.muted("원문 근거 · " + evidence.text());
                pdf.link(evidence.articleTitle(), evidence.canonicalUrl());
            }
        }
    }

    private static List<ReportChanges.Item> briefItems(ReportChanges changes) {
        return changes.items().stream().filter(item -> item.type() != ReportChanges.Type.UNCHANGED)
                .sorted(Comparator.comparingInt(item -> switch (item.type()) {
                    case REFUTATION -> 0; case UPDATED -> 1; case NEWLY_INCLUDED -> 2; default -> 3;
                })).filter(item -> shortEnough(item.title(), 110) && shortEnough(item.summary(), 240)).limit(3).toList();
    }
    private static boolean shortEnough(String value, int limit) { return value == null || value.codePointCount(0, value.length()) <= limit; }
    private static String type(ReportChanges.Type type) {
        return switch (type) {
            case NEWLY_INCLUDED -> "새로 포함"; case UPDATED -> "내용 업데이트"; case REFUTATION -> "반박·정정 근거";
            case UNCHANGED -> "같은 내용"; case UNDETERMINED -> "판단 보류";
        };
    }
    private static String counts(ReportChanges changes) {
        return java.util.Arrays.stream(ReportChanges.Type.values()).map(type -> type(type) + " "
                + changes.items().stream().filter(item -> item.type() == type).count() + "건")
                .collect(java.util.stream.Collectors.joining(" · "));
    }
    private static String periods(ReportChanges changes) {
        if (changes.periods() != null) return "이전 " + (changes.periods().previousLabel() == null ? "비교 자료 없음" : changes.periods().previousLabel())
                + " → 이번 " + changes.periods().currentLabel();
        return changes.baseReportDate() == null ? "비교 기준 자료를 확인하고 있습니다."
                : "이전 " + changes.baseReportDate() + " → 이번 " + changes.reportDate();
    }
    private static String denominator(ReportChanges.KeywordChanges changes) {
        return "유효 이슈 분모 · 이전 " + changes.previousDenominatorIssueCount() + "건 / 이번 "
                + changes.currentDenominatorIssueCount() + "건 · 상위 선정 전 기준";
    }
    private static String amount(ReportChanges.KeywordChange item) {
        return item.previousIssueCount() + "건 → " + item.currentIssueCount() + "건 · "
                + share(item.previousSharePercent()) + " → " + share(item.currentSharePercent())
                + " · " + (item.changePercentagePoints() == null ? "비중 차이 계산 안 함"
                : (item.changePercentagePoints() > 0 ? "+" : "") + number(item.changePercentagePoints()) + "%p")
                + (item.previousIssueCount() == 0 && item.currentIssueCount() > 0 ? " · 신규 집계" : "");
    }
    private static String share(Double value) { return value == null ? "비중 없음" : number(value) + "%"; }
    private static double bounded(Double value) { return value == null || !Double.isFinite(value) ? 0 : Math.max(0, Math.min(100, value)); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static void bar(StringBuilder html, Double share, String color) {
        double width = bounded(share);
        html.append("<table role=\"presentation\" aria-hidden=\"true\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"table-layout:fixed;margin-top:4px\"><tbody><tr>");
        if (width > 0) html.append("<td width=\"").append(number(width)).append("%\" height=\"5\" bgcolor=\"").append(color).append("\" style=\"height:5px;line-height:0;font-size:0\"></td>");
        if (width < 100) html.append("<td width=\"").append(number(100-width)).append("%\" height=\"5\" bgcolor=\"#e2e8f0\" style=\"height:5px;line-height:0;font-size:0\"></td>");
        html.append("</tr></tbody></table>");
    }
    private static String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
    private static void note(StringBuilder html, String value) {
        html.append("<p style=\"margin:7px 0 0;font-size:12px;line-height:1.7;color:#636c78;overflow-wrap:anywhere;word-break:break-word\">")
                .append(escape(value)).append("</p>");
    }
}
