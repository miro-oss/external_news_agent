package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 비활성·장애 시에도 STUB finding을 오염시키지 않는 결정적 fallback 보고서를 만든다. */
@Component
public class ReportGenerator {

    public static final String MODEL_NAME = "safe-fallback-report-v1";

    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ReportDocument generate(List<Finding> findings, LocalDateTime generatedAt) {
        return generate(findings, generatedAt, ReportSourceStats.empty());
    }

    public ReportDocument generate(List<Finding> findings,
                                   LocalDateTime generatedAt,
                                   ReportSourceStats sourceStats) {
        int actualStubCount = (int) findings.stream()
                .filter(finding -> finding.getAnalysisSource() == AnalysisSource.STUB)
                .count();
        ReportSourceStats effectiveStats = new ReportSourceStats(
                sourceStats.collected(),
                sourceStats.blocked(),
                sourceStats.failed(),
                sourceStats.paywalled(),
                Math.max(sourceStats.stubExcluded(), actualStubCount));
        List<Finding> ordered = ReportFindingOrder.sort(findings.stream()
                .filter(finding -> finding.getAnalysisSource() != AnalysisSource.STUB)
                .toList());
        String title = title(ordered, generatedAt);
        return new ReportDocument(
                title,
                markdown(title, ordered, effectiveStats),
                MODEL_NAME,
                null,
                null,
                null,
                null,
                null,
                null,
                ReportStatus.FALLBACK);
    }

    private String title(List<Finding> findings, LocalDateTime generatedAt) {
        List<String> topics = findings.stream()
                .map(finding -> finding.getArticle().getTopic().getName())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        String prefix = topics.size() == 1 ? topics.getFirst() + " 뉴스" : topics.isEmpty() ? "뉴스" : "통합 뉴스";
        return truncateUtf8(prefix + " 보고서 " + generatedAt.format(TITLE_TIME), NewsReport.MAX_TITLE_LENGTH);
    }

    private String markdown(String title, List<Finding> findings, ReportSourceStats sourceStats) {
        StringBuilder body = new StringBuilder("# ").append(singleLine(title)).append("\n\n");
        body.append("## 오늘의 핵심\n\n");
        if (findings.isEmpty()) {
            body.append("- 실제 LLM 분석 finding이 없어 포함할 핵심 요약이 없습니다.\n");
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
            finding.getEffectiveKeyPoints().forEach(point -> body
                    .append("- 핵심: ").append(singleLine(point.text())).append("\n"));
            if (StringUtils.hasText(finding.getArticle().getCanonicalUrl())) {
                body.append("- 원문: <").append(finding.getArticle().getCanonicalUrl().trim()).append(">\n");
            }
        }
        appendSourceNotes(body, sourceStats);
        return body.toString();
    }

    private void appendSourceNotes(StringBuilder body, ReportSourceStats stats) {
        body.append("\n## 수집 및 출처 참고\n\n");
        boolean hasNote = false;
        if (stats.stubExcluded() > 0) {
            body.append("- STUB 분석 ").append(stats.stubExcluded())
                    .append("건은 실제 LLM 분석이 아니므로 보고서에서 제외했습니다.\n");
            hasNote = true;
        }
        if (stats.paywalled() > 0) {
            body.append("- 페이월로 전문을 확인하지 못한 기사가 ").append(stats.paywalled())
                    .append("건 있습니다.\n");
            hasNote = true;
        }
        int otherBlocked = Math.max(stats.blocked() - stats.paywalled(), 0);
        if (otherBlocked > 0) {
            body.append("- robots 또는 접근 제한으로 본문이 차단된 기사가 ")
                    .append(otherBlocked).append("건 있습니다.\n");
            hasNote = true;
        }
        if (stats.failed() > 0) {
            body.append("- 본문 수집에 실패한 기사가 ").append(stats.failed()).append("건 있습니다.\n");
            hasNote = true;
        }
        if (!hasNote) {
            body.append("- 수집 또는 분석 제외 사항이 없습니다.\n");
        }
    }

    private Map<String, Long> counts(List<Finding> findings,
                                     java.util.function.Function<Finding, String> classifier) {
        Map<String, Long> counts = new LinkedHashMap<>();
        findings.forEach(finding -> counts.merge(classifier.apply(finding), 1L, Long::sum));
        return counts;
    }

    private String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncateUtf8(String value, int maxBytes) {
        int byteCount = 0;
        int endIndex = 0;
        while (endIndex < value.length()) {
            int codePoint = value.codePointAt(endIndex);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + codePointBytes > maxBytes) {
                break;
            }
            byteCount += codePointBytes;
            endIndex += Character.charCount(codePoint);
        }
        return endIndex == value.length() ? value : value.substring(0, endIndex);
    }
}
