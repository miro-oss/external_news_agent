package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;

import java.util.List;

/** Render a safe view of legacy snapshots without changing their stored generation evidence. */
public record ReportReadingContent(String markdownBody, ReportContent structuredContent) {
    public static ReportReadingContent from(NewsReport report, ReportFindings.Visible visible) {
        if (!visible.filtered()) {
            return new ReportReadingContent(report.getMarkdownBody(), report.getStructuredContent());
        }
        // Executive summaries/legacy markdown do not carry per-sentence finding references.
        // Once any source is hidden, derive a view only from still available, supported evidence.
        List<Finding> supported = visible.findings().stream()
                .filter(f -> AnalysisSource.isLlmDerived(f.getAnalysisSource()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence).toList();
        List<String> summaries = supported.stream().map(FindingEvidencePolicy::reportSummary).limit(3).toList();
        ReportContent content = new ReportContent(summaries,
                supported.stream().map(f -> new ReportContent.ImportantEvent(f.getArticle().getTitle(),
                        FindingEvidencePolicy.reportSummary(f), "", List.of(f.getId()))).toList(),
                List.of(), List.of("본문을 확보한 기사의 확인된 내용만 표시합니다."));
        StringBuilder markdown = new StringBuilder(report.getReportScope() == com.example.be.domain.reports.entity.ReportScope.WEEKLY
                ? "## 이번 주 핵심\n\n" : "## 오늘의 핵심\n\n");
        if (summaries.isEmpty()) {
            markdown.append("- 본문에서 근거를 확인할 수 있는 기사가 없습니다.\n");
        } else {
            summaries.forEach(summary -> markdown.append("- ").append(ReportMarkdown.text(summary)).append('\n'));
        }
        for (Finding finding : supported) {
            markdown.append("\n### ").append(ReportMarkdown.text(finding.getArticle().getTitle())).append("\n\n")
                    .append(ReportMarkdown.text(FindingEvidencePolicy.reportSummary(finding))).append("\n");
            FindingEvidencePolicy.supportedKeyPoints(finding).forEach(point -> markdown.append("- ")
                    .append(ReportMarkdown.text(point.text())).append('\n'));
            String url = ReportMarkdown.httpUrl(finding.getArticle().getCanonicalUrl());
            if (url != null) markdown.append("- 원문: <").append(url).append(">\n");
        }
        return new ReportReadingContent(markdown.toString(), report.getStructuredContent() == null ? null : content);
    }
}
