package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class NotificationDartSummary {
    private static final String LINK_STYLE = " style=\"color:#1b64da;text-decoration:underline\"";
    private static final String TABLE = "<table role=\"presentation\" width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse\">";
    private final ReportDartService reportDartService;

    public String render(NewsReport report, String reportUrl) {
        return render(report, reportUrl, null, Map.of());
    }

    public String render(NewsReport report, String reportUrl, ReportEventFeedbackProjection.View view,
                         Map<String, Integer> companyPages) {
        return render(reportUrl, companyPages, project(report, view));
    }

    List<CompanyAttachment> project(NewsReport report, ReportEventFeedbackProjection.View view) {
        var stored = reportDartService.getStored(report);
        return stored == null ? List.of() : NotificationCompanyVisibility.visible(report, view, stored.companies());
    }

    String render(String reportUrl, Map<String, Integer> companyPages, List<CompanyAttachment> companies) {
        if (companies.isEmpty()) return "";
        var html = new StringBuilder(TABLE).append("<tbody><tr><td style=\"padding:20px 18px;background:#f9fafb;border-radius:12px\">"
                + "<h2 style=\"margin:0 0 12px;color:#4e5968;font-size:14px\">회사 실적 · 공시</h2>");
        companies.forEach(company -> appendCompany(html, company));
        return html.append("</td></tr></tbody></table>").toString();
    }

    private void appendCompany(StringBuilder html, CompanyAttachment entry) {
        html.append("<section style=\"margin:12px 0\"><h3 style=\"margin:0 0 6px;color:#191f28;font-size:14px\">")
                .append(escape(limit(entry.company().corpName(), 40))).append("</h3>");
        boolean ready = "READY".equals(entry.status()) || "PARTIAL".equals(entry.status());
        if (!ready || entry.data() == null) {
            html.append("<p style=\"margin:4px 0;color:#636c78;font-size:12px\">").append(switch (entry.status()) {
                case "FAILED" -> "공시 자료 확인 실패";
                case "DISABLED" -> "공시 자료 확인 불가";
                case "READY", "PARTIAL" -> "공시 자료 없음";
                default -> "공시 자료 준비 중";
            }).append("</p></section>");
            return;
        }
        FinancialData data = entry.data();
        var quarters = CompanyFinancialBrief.quarters(data);
        html.append("<p style=\"margin:4px 0 8px;color:#636c78;font-size:11px\">")
                .append(escape(CompanyFinancialBrief.basis(data.fsDiv()))).append(" · 단위: ")
                .append(escape(CompanyFinancialBrief.unit(data.currency()))).append("</p>");
        if (quarters.isEmpty()) html.append("<p style=\"color:#636c78;font-size:12px\">분기 실적 자료 없음</p>");
        else {
            chart(html, quarters, data.currency());
            numbers(html, quarters, data.currency());
        }
        var filing = CompanyFinancialBrief.latestFiling(data);
        if (filing != null) {
            html.append("<p style=\"margin:8px 0 0;color:#4e5968;font-size:12px\">최근 공시 · ");
            if (filing.corrected() && (filing.reportName() == null || !filing.reportName().contains("정정"))) html.append("[정정] ");
            String title = escape(limit(filing.reportName(), 70));
            if (DartFilingLinks.isSafe(filing)) html.append("<a href=\"").append(escape(filing.url())).append('"')
                    .append(LINK_STYLE).append('>').append(title).append("</a>");
            else html.append(title);
            html.append(" · ").append(escape(limit(filing.filedAt(), 10))).append("</p>");
        }
        if ("PARTIAL".equals(entry.status())) html.append("<p style=\"margin:4px 0;color:#636c78;font-size:11px\">일부 자료 없음</p>");
        html.append("</section>");
    }

    private void chart(StringBuilder html, List<Quarter> quarters, String currency) {
        BigDecimal revenueMax = maximum(quarters, Quarter::revenue);
        BigDecimal profitMax = maximum(quarters, Quarter::operatingProfit);
        html.append(TABLE).append("<tbody><tr><td style=\"width:72px\"></td><td style=\"color:#1b64da;font-size:11px\">매출</td>"
                + "<td style=\"color:#32836a;font-size:11px\">영업이익</td></tr>");
        for (var quarter : quarters) {
            html.append("<tr><td style=\"padding:4px 0;font-size:11px;color:#636c78\">")
                    .append(quarter.year()).append(" Q").append(quarter.quarter()).append("</td>");
            bar(html, quarter.revenue(), currency, revenueMax, "#1b64da");
            bar(html, quarter.operatingProfit(), currency, profitMax, "#32836a");
            html.append("</tr>");
        }
        html.append("</tbody></table><p style=\"margin:4px 0 8px;color:#636c78;font-size:10px\">막대는 항목별 절댓값 기준 · 음수는 빨간색</p>");
    }

    private BigDecimal maximum(List<Quarter> quarters, Function<Quarter, String> metric) {
        return quarters.stream().map(metric).map(CompanyFinancialBrief::value).filter(value -> value != null)
                .map(BigDecimal::abs).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private void bar(StringBuilder html, String raw, String currency, BigDecimal maximum, String positiveColor) {
        BigDecimal value = CompanyFinancialBrief.value(raw);
        html.append("<td style=\"width:40%;padding:4px 8px 4px 0\">");
        if (value == null || value.signum() == 0) html.append("<span style=\"color:#636c78;font-size:11px\">")
                .append(value == null ? "자료 없음" : "0").append("</span>");
        else {
            int width = Math.max(1, value.abs().multiply(BigDecimal.valueOf(100)).divide(maximum, 0, RoundingMode.HALF_UP).intValue());
            html.append("<table role=\"presentation\" width=\"").append(width).append("%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" aria-label=\"")
                    .append(escape(CompanyFinancialBrief.amount(raw, currency))).append("\"><tbody><tr><td height=\"9\" bgcolor=\"")
                    .append(value.signum() < 0 ? "#d14b4b" : positiveColor)
                    .append("\" style=\"height:9px;font-size:0;line-height:0\">&nbsp;</td></tr></tbody></table>");
        }
        html.append("</td>");
    }

    private void numbers(StringBuilder html, List<Quarter> quarters, String currency) {
        html.append("<table width=\"100%\" border=\"0\" cellpadding=\"5\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:12px;color:#4e5968;text-align:right\">"
                + "<thead><tr><th scope=\"col\" style=\"text-align:left;border-bottom:1px solid #dfe3e8\">분기</th>"
                + "<th scope=\"col\" style=\"border-bottom:1px solid #dfe3e8\">매출</th><th scope=\"col\" style=\"border-bottom:1px solid #dfe3e8\">영업이익</th></tr></thead><tbody>");
        for (var quarter : quarters) html.append("<tr><th scope=\"row\" style=\"text-align:left;font-weight:400\">")
                .append(quarter.year()).append("년 ").append(quarter.quarter()).append("분기").append(quarter.derived() ? "*" : "")
                .append("</th><td>").append(escape(CompanyFinancialBrief.number(quarter.revenue(), currency)))
                .append("</td><td>").append(escape(CompanyFinancialBrief.number(quarter.operatingProfit(), currency))).append("</td></tr>");
        html.append("</tbody></table>");
        if (quarters.stream().anyMatch(Quarter::derived)) html.append("<p style=\"margin:2px 0;color:#636c78;font-size:10px\">* 산출값</p>");
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        String singleLine = value.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.codePointCount(0, singleLine.length()) <= maximum ? singleLine
                : singleLine.substring(0, singleLine.offsetByCodePoints(0, maximum - 1)) + "…";
    }

    private String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
}
