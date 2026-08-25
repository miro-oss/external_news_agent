package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
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
        int actualEvidenceExcluded = (int) findings.stream()
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(finding -> !ReportEvidencePolicy.hasSupportedEvidence(finding))
                .count();
        ReportSourceStats effectiveStats = new ReportSourceStats(
                sourceStats.collected(),
                sourceStats.blocked(),
                sourceStats.failed(),
                sourceStats.paywalled(),
                Math.max(sourceStats.stubExcluded(), actualStubCount),
                Math.max(sourceStats.evidenceExcluded(), actualEvidenceExcluded));
        List<Finding> ordered = ReportFindingOrder.sort(findings.stream()
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(ReportEvidencePolicy::hasSupportedEvidence)
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
        StringBuilder body = new StringBuilder("# ").append(markdownText(title)).append("\n\n");
        body.append("## 오늘의 핵심\n\n");
        if (findings.isEmpty()) {
            body.append("- 이번 실행에서 기사 ").append(sourceStats.collected())
                    .append(sourceStats.evidenceExcluded() > 0
                            ? "건을 관측했지만 근거가 확인된 분석 결과가 없어 기사 내용을 요약하지 않았습니다.\n"
                            : "건을 관측했지만 분석 결과가 없어 기사 내용을 요약하지 않았습니다.\n");
        } else {
            findings.stream().limit(5).forEach(finding -> body
                    .append("- ").append(markdownText(
                            ReportEvidencePolicy.reportSummary(finding))).append("\n"));
        }

        Map<String, Long> riskCounts = counts(findings, finding -> finding.getRiskLevel().toApiValue());
        Map<String, Long> categoryCounts = counts(findings, Finding::getCategory);
        // 집계 키는 apiValue 그대로 두고 찍을 때만 한글로 바꾼다. 키까지 한글로 만들면
        // 같은 값이 코드 안에서 두 이름을 갖게 된다.
        body.append("\n## 요약 통계\n\n")
                .append("- 전체 근거: ").append(findings.size()).append("건\n")
                .append("- 위험도: 높음 ").append(riskCounts.getOrDefault("high", 0L))
                .append(" · 보통 ").append(riskCounts.getOrDefault("medium", 0L))
                .append(" · 낮음 ").append(riskCounts.getOrDefault("low", 0L)).append("\n");
        if (!categoryCounts.isEmpty()) {
            body.append("- 분류: ");
            body.append(categoryCounts.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue())
                    .reduce((left, right) -> left + " · " + right)
                    .orElse(""));
            body.append("\n");
        }

        body.append("\n## 기사별 분석\n");
        if (findings.isEmpty()) {
            body.append("\n원문에서 근거를 확인하지 못한 분석은 보고서에 담지 않았습니다.\n");
        }
        for (Finding finding : findings) {
            body.append("\n### ").append(markdownText(finding.getArticle().getTitle())).append("\n\n")
                    .append(markdownText(ReportEvidencePolicy.reportSummary(finding))).append("\n\n")
                    .append("- 분류: ").append(markdownText(finding.getCategory()))
                    .append(" · 위험도: ").append(ReportLabels.risk(finding.getRiskLevel()))
                    .append(" · 관련도: ").append(ReportLabels.relevance(finding.getRelevance())).append("\n");
            ReportEvidencePolicy.supportedKeyPoints(finding).forEach(point -> body
                    .append("- 핵심: ").append(markdownText(point.text())).append("\n"));
            if (safeHttpUrl(finding.getArticle().getCanonicalUrl())) {
                body.append("- 원문: <").append(finding.getArticle().getCanonicalUrl().trim()).append(">\n");
            }
        }
        appendSourceNotes(body, sourceStats);
        return body.toString();
    }

    private void appendSourceNotes(StringBuilder body, ReportSourceStats stats) {
        body.append("\n## 수집 및 출처 참고\n\n");
        ReportSourceNotes.from(stats).forEach(note -> body.append("- ").append(markdownText(note)).append("\n"));
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

    private String markdownText(String value) {
        return singleLine(value).replaceAll("([\\\\`*_{}\\[\\]<>()#+!|])", "\\\\$1");
    }

    private boolean safeHttpUrl(String value) {
        if (!StringUtils.hasText(value)
                || value.chars().anyMatch(character -> Character.isWhitespace(character)
                || character == '<' || character == '>')) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
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
