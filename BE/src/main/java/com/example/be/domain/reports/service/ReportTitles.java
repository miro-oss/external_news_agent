package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class ReportTitles {
    private ReportTitles() {}

    static String forReport(NewsReport report, String fallback, LocalDateTime generatedAt) {
        if (report.getReportScope() == ReportScope.DAILY) {
            return report.getReportDate() == null ? fallback : report.getReportDate() + " 일일 통합 뉴스 보고서";
        }
        List<String> topics = report.getCollectionContexts().stream().flatMap(context -> context.topics().stream())
                .map(topic -> topic.topicName()).filter(name -> name != null && !name.isBlank()).distinct().toList();
        if (topics.isEmpty()) return fallback;
        String first = topics.getFirst();
        // Oracle's existing title limit is measured in UTF-8 bytes. Keep the subject legible without overflow.
        String subject = first.codePointCount(0, first.length()) > 60
                ? first.substring(0, first.offsetByCodePoints(0, 60)) + "…" : first;
        if (topics.size() > 1) subject += " 외 " + (topics.size() - 1) + "개 주제";
        String date = generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return subject + " · " + date + " 리포트";
    }

    static String alignMarkdownTitle(String markdown, String title) {
        var match = java.util.regex.Pattern.compile("\\A(\\s*)# [^\\r\\n]*").matcher(markdown);
        return match.find() ? match.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                match.group(1) + "# " + ReportMarkdown.text(title))) : markdown;
    }
}
