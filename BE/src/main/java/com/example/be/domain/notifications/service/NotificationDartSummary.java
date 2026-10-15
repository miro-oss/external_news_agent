package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class NotificationDartSummary {
    private static final int MAX_COMPANIES = 3;
    private static final String LINK_STYLE = " style=\"color:#1b64da;text-decoration:underline\"";
    private final ReportDartService reportDartService;

    public String render(NewsReport report, String reportUrl) {
        if (report.getReportScope() != ReportScope.RUN) return "";
        var companies = reportDartService.getStored(report).companies();
        if (companies.isEmpty()) return "";
        var html = new StringBuilder("<table role=\"presentation\" width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;text-align:left;margin:0 0 20px\"><tbody><tr><td "
                + "style=\"padding:16px 18px;background:#f9fafb;border-radius:12px\">"
                + "<h2 style=\"margin:0 0 8px;color:#4e5968;font-size:13px;font-weight:700\">회사 실적 · 공시</h2>");
        companies.stream().limit(MAX_COMPANIES).forEach(company -> appendCompany(html, company));
        int remaining = companies.size() - MAX_COMPANIES;
        if (remaining > 0 || safeReportUrl(reportUrl)) {
            html.append("<p style=\"margin:8px 0 0;color:#636c78;font-size:12px\">");
            if (remaining > 0) html.append("외 ").append(remaining).append("개 회사");
            if (safeReportUrl(reportUrl)) {
                if (remaining > 0) html.append(" · ");
                html.append("<a href=\"").append(escape(reportUrl)).append('"').append(LINK_STYLE)
                        .append(">실적·공시 자세히</a>");
            }
            html.append("</p>");
        }
        return html.append("</td></tr></tbody></table>").toString();
    }

    private void appendCompany(StringBuilder html, CompanyAttachment entry) {
        html.append("<p style=\"margin:8px 0 0;color:#191f28;font-size:13px;line-height:1.7\"><strong>")
                .append(escape(limit(entry.company().corpName(), 40))).append("</strong><br>");
        boolean ready = "READY".equals(entry.status()) || "PARTIAL".equals(entry.status());
        if (!ready || entry.data() == null) {
            html.append(switch (entry.status()) {
                case "FAILED" -> "공시 자료 확인 실패";
                case "DISABLED" -> "공시 자료 확인 불가";
                case "READY", "PARTIAL" -> "공시 자료 없음";
                default -> "공시 자료 준비 중";
            }).append("</p>");
            return;
        }
        FinancialData data = entry.data();
        html.append(escape(metrics(data))).append("</p>");
        if (!data.disclosures().isEmpty()) {
            Filing filing = data.disclosures().getFirst();
            html.append("<p style=\"margin:2px 0 0;color:#4e5968;font-size:12px;line-height:1.6\">최근 공시 · ");
            if (filing.corrected()) html.append("[정정] ");
            String title = escape(limit(filing.reportName(), 70));
            if (safeFilingUrl(filing)) html.append("<a href=\"").append(escape(filing.url())).append('"')
                    .append(LINK_STYLE).append('>').append(title).append("</a>");
            else html.append(title);
            html.append(" · ").append(escape(limit(filing.filedAt(), 10))).append("</p>");
        }
        html.append("<p style=\"margin:2px 0 0;color:#636c78;font-size:11px;line-height:1.6\">");
        if ("PARTIAL".equals(entry.status())) html.append("일부 자료 없음 · ");
        html.append(data.fetchedAt() == null ? "자료 확인 시각 미상" : "자료 확인 "
                + data.fetchedAt().atZoneSameInstant(ApiTimeZone.ZONE).toLocalDate() + " (한국시간)")
                .append("</p>");
    }

    private String metrics(FinancialData data) {
        if (data.quarters().isEmpty()) return "분기 실적 자료 없음";
        var quarter = data.quarters().getLast();
        String basis = "CFS".equals(data.fsDiv()) ? "연결" : "OFS".equals(data.fsDiv()) ? "별도" : "재무 기준 미확인";
        return quarter.year() + "년 " + quarter.quarter() + "분기" + (quarter.derived() ? "(산출값)" : "")
                + " · " + basis + " · 매출 " + amount(quarter.revenue(), data.currency())
                + " · 영업이익 " + amount(quarter.operatingProfit(), data.currency());
    }

    private String amount(String value, String currency) {
        if (value == null || value.length() > 128 || !value.matches("-?[0-9]+(?:\\.[0-9]+)?")) return "자료 없음";
        BigDecimal amount = new BigDecimal(value);
        if ("KRW".equals(currency)) {
            BigDecimal exact = amount.movePointLeft(8);
            BigDecimal rounded = exact.setScale(1, RoundingMode.HALF_UP);
            amount = (rounded.signum() == 0 && exact.signum() != 0 ? exact : rounded).stripTrailingZeros();
        }
        var format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.KOREA));
        format.setMinimumFractionDigits(Math.max(0, amount.scale()));
        format.setMaximumFractionDigits(Math.max(0, amount.scale()));
        String unit = "KRW".equals(currency) ? "억원" : currency != null && currency.matches("[A-Z]{3}")
                ? " " + currency : " (통화 미확인)";
        return format.format(amount) + unit;
    }

    private boolean safeFilingUrl(Filing filing) {
        if (filing.rceptNo() == null || !filing.rceptNo().matches("[0-9]{14}")) return false;
        URI uri = parseUrl(filing.url());
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && "dart.fss.or.kr".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getFragment() == null
                && "/dsaf001/main.do".equals(uri.getRawPath())
                && ("rcpNo=" + filing.rceptNo()).equals(uri.getRawQuery());
    }

    private boolean safeReportUrl(String value) {
        URI uri = parseUrl(value);
        return uri != null && uri.getUserInfo() == null && uri.getHost() != null
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
    }

    private URI parseUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048 || value.chars().anyMatch(character ->
                Character.isWhitespace(character) || Character.isISOControl(character) || character == '<' || character == '>')) return null;
        try { return new URI(value); }
        catch (URISyntaxException exception) { return null; }
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        String singleLine = value.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.codePointCount(0, singleLine.length()) <= maximum ? singleLine
                : singleLine.substring(0, singleLine.offsetByCodePoints(0, maximum - 1)) + "…";
    }

    private String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
}
