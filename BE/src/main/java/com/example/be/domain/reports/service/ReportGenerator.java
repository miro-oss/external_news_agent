package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M5에서는 외부 모델 없이 같은 findings가 언제나 같은 본문을 만들도록 한다. */
@Component
public class ReportGenerator {

    public static final String MODEL_NAME = "stub-report-v1";

    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ReportDocument generate(List<Finding> findings, LocalDateTime generatedAt) {
        List<Finding> ordered = findings.stream()
                .sorted(Comparator
                        .comparingInt((Finding finding) -> riskOrder(finding.getRiskLevel())).reversed()
                        .thenComparingInt(finding -> relevanceOrder(finding.getRelevance()))
                        .thenComparing(Finding::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String title = title(ordered, generatedAt);
        return new ReportDocument(title, markdown(title, ordered), MODEL_NAME);
    }

    private String title(List<Finding> findings, LocalDateTime generatedAt) {
        List<String> topics = findings.stream()
                .map(finding -> finding.getArticle().getTopic().getName())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        String prefix = topics.size() == 1 ? topics.getFirst() + " 뉴스" : topics.isEmpty() ? "뉴스" : "통합 뉴스";
        return truncate(prefix + " 보고서 " + generatedAt.format(TITLE_TIME), 500);
    }

    private String markdown(String title, List<Finding> findings) {
        StringBuilder body = new StringBuilder("# ").append(singleLine(title)).append("\n\n");
        body.append("## 오늘의 핵심\n\n");
        if (findings.isEmpty()) {
            body.append("- 이번 실행에서 새로 분석된 기사가 없습니다.\n");
        } else {
            findings.stream().limit(5).forEach(finding -> body
                    .append("- ").append(singleLine(finding.getSummary())).append("\n"));
        }

        Map<String, Long> riskCounts = counts(findings, finding -> finding.getRiskLevel().toApiValue());
        Map<String, Long> categoryCounts = counts(findings, Finding::getCategory);
        body.append("\n## 요약 통계\n\n")
                .append("- 전체 finding: ").append(findings.size()).append("건\n")
                .append("- 위험도: high ").append(riskCounts.getOrDefault("high", 0L))
                .append(" · medium ").append(riskCounts.getOrDefault("medium", 0L))
                .append(" · low ").append(riskCounts.getOrDefault("low", 0L)).append("\n");
        if (!categoryCounts.isEmpty()) {
            body.append("- 카테고리: ");
            body.append(categoryCounts.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue())
                    .reduce((left, right) -> left + " · " + right)
                    .orElse(""));
            body.append("\n");
        }

        body.append("\n## 기사별 분석\n");
        if (findings.isEmpty()) {
            body.append("\n분석할 기사가 없습니다.\n");
        }
        for (Finding finding : findings) {
            body.append("\n### ").append(singleLine(finding.getArticle().getTitle())).append("\n\n")
                    .append(singleLine(finding.getSummary())).append("\n\n")
                    .append("- 분류: ").append(singleLine(finding.getCategory()))
                    .append(" · 위험도: ").append(finding.getRiskLevel().toApiValue())
                    .append(" · 관련도: ").append(finding.getRelevance().toApiValue()).append("\n");
            finding.getKeyPoints().forEach(point -> body
                    .append("- 핵심: ").append(singleLine(point.text())).append("\n"));
            if (StringUtils.hasText(finding.getArticle().getCanonicalUrl())) {
                body.append("- 원문: <").append(finding.getArticle().getCanonicalUrl().trim()).append(">\n");
            }
        }
        return body.toString();
    }

    private Map<String, Long> counts(List<Finding> findings,
                                     java.util.function.Function<Finding, String> classifier) {
        Map<String, Long> counts = new LinkedHashMap<>();
        findings.forEach(finding -> counts.merge(classifier.apply(finding), 1L, Long::sum));
        return counts;
    }

    private int riskOrder(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private int relevanceOrder(Relevance relevance) {
        return switch (relevance) {
            case IMPORTANT -> 1;
            case WATCH -> 2;
            case REFERENCE -> 3;
        };
    }

    private String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
