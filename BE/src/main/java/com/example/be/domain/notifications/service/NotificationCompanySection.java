package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.dto.res.ReportDartDTO.FilingRow;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Navigation is bound to saved, visible article evidence rather than company-name similarity. */
final class NotificationCompanySection {
    private NotificationCompanySection() { }

    static void email(StringBuilder html, List<Long> findingIds, List<CompanyAttachment> companies,
                      Map<String, Integer> companyPages) {
        var linked = linked(findingIds, companies);
        if (linked.isEmpty()) return;
        html.append("<p style=\"margin:8px 0 12px;color:#1b64da;font-size:12px;line-height:1.8\">확인된 기업 · ");
        for (int i = 0; i < linked.size(); i++) {
            if (i > 0) html.append("<br>");
            var entry = linked.get(i);
            html.append(HtmlUtils.htmlEscape(entry.company().corpName(), "UTF-8"));
            Integer page = companyPages.get(entry.company().corpCode());
            html.append(" · 첨부 PDF ").append(page == null ? "회사별 부록" : "p. " + page);
        }
        html.append("</p>");
    }

    static void pdf(ReportPdfLayout pdf, List<Long> findingIds, List<CompanyAttachment> companies) throws IOException {
        for (var entry : linked(findingIds, companies))
            pdf.internalLink("확인된 기업 · " + entry.company().corpName() + " · 실적·공시", "company-" + companies.indexOf(entry));
    }

    private static List<CompanyAttachment> linked(List<Long> findingIds, List<CompanyAttachment> companies) {
        return companies.stream().filter(company -> company.issueLinks().stream()
                .anyMatch(link -> findingIds.contains(link.findingId()))).toList();
    }

    static String roles(FilingRow row) {
        return row.roles().contains("RECENT") ? row.roles().contains("FINANCIAL_SOURCE")
                ? "최근 공시\n실적 근거" : "최근 공시" : "실적 근거";
    }

    static String relevance(FilingRow row) {
        return "DIRECT_REFERENCE".equals(row.relevance()) ? "기사에서 직접 인용" : "회사 참고";
    }
}
