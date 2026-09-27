package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Topic restriction precedes weekly ranking; global DAILY top-N never determines these inputs. */
@Component
@RequiredArgsConstructor
public class TopicWeeklyReportSelector {
    private final FindingRepository findings;
    private final IssueArticleRepository memberships;
    private final TopicRelevancePolicy relevance;
    @org.springframework.beans.factory.annotation.Value("${news.reports.daily.max-issues:10}")
    private int maxIssues = 10;

    public Selection select(Long topicId, String topicName, LocalDate date) {
        List<Finding> candidates = findings.findDailyReportCandidates(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        var links = OracleInClause.batches(candidates.stream().map(f -> f.getArticle().getId()).distinct().toList())
                .stream().flatMap(ids -> memberships.findByArticleIds(ids).stream())
                .filter(link -> link.getIssue().getArticleCount() > 0 && link.getIssue().getTopic().getId().equals(topicId))
                .collect(Collectors.groupingBy(link -> link.getArticle().getId(),
                        Collectors.mapping(link -> link.getIssue(), Collectors.toList())));
        Map<Long, NewsIssue> byFinding = new LinkedHashMap<>();
        // Do not filter rejected/unsupported latest observations early and revive older claims.
        Map<Long, Finding> latest = new LinkedHashMap<>();
        relevance.topicContextFindings(topicId, candidates).stream().sorted(Comparator.comparing((Finding f) -> f.getRun().getStartedAt()).reversed()
                .thenComparing(Finding::getId, Comparator.reverseOrder())).forEach(f -> {
                    links.getOrDefault(f.getArticle().getId(), List.of()).stream().min(Comparator.comparing(NewsIssue::getId))
                            .ifPresent(issue -> {
                                byFinding.put(f.getId(), issue);
                                latest.putIfAbsent(issue.getId(), f);
                            });
                });
        List<Finding> eligible = relevance.filterDailyTopicFindings(date, topicId, List.copyOf(latest.values())).stream()
                .filter(ReportFindings::hasFullText)
                .filter(f -> AnalysisSource.isLlmDerived(f.getAnalysisSource()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence)
                .filter(f -> FindingEvidencePolicy.supportedKeyPoints(f).stream().allMatch(point ->
                        List.of("FACT", "FORECAST", "OPINION").contains(point.claimType())
                                && (!"OPINION".equals(point.claimType())
                                    || point.attributedTo() != null && !point.attributedTo().isBlank())))
                .filter(TopicWeeklyReportSelector::hasSavedSentences)
                .sorted(Comparator.comparing((Finding f) -> byFinding.get(f.getId()).getImportanceScore(),
                        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(f -> byFinding.get(f.getId()).getId()))
                .limit(Math.max(1, Math.min(50, maxIssues))).toList();
        var snapshot = ReportComparisonSnapshot.captureByFinding(eligible, byFinding);
        // Only supported claims enter the immutable summary, never an ungrounded finding.summary.
        var events = eligible.stream().flatMap(finding -> FindingEvidencePolicy.supportedKeyPoints(finding).stream()
                .map(point -> new ReportContent.ImportantEvent(finding.getArticle().getTitle(), qualified(point),
                        "저장된 기사 문장 근거를 바탕으로 정리했습니다.", List.of(finding.getId())))).toList();
        ReportContent content = new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                events, List.of(), List.of());
        var source = new WeeklyReportInput.DailySource(null, date, topicName + " · " + date + " 분석", "", content,
                eligible.stream().map(Finding::getId).toList(), snapshot);
        return new Selection(source, eligible);
    }

    private static String qualified(FindingKeyPoint point) {
        String prefix = "FORECAST".equals(point.claimType()) ? "[전망] "
                : "OPINION".equals(point.claimType()) ? "[" + point.attributedTo().trim() + "의 의견] " : "";
        if ("weak".equals(point.groundedness())) {
            prefix = "[근거 제한" + (point.groundingReason() == null || point.groundingReason().isBlank()
                    ? "" : ": " + point.groundingReason().trim()) + "] " + prefix;
        }
        return prefix + point.text();
    }

    private static boolean hasSavedSentences(Finding finding) {
        var sections = finding.getSections();
        return sections != null && FindingEvidencePolicy.supportedKeyPoints(finding).stream().allMatch(point ->
                point.evidence().stream().allMatch(id -> sections.stream().anyMatch(section -> section.index() == id
                        && section.text() != null && !section.text().isBlank())));
    }

    public record Selection(WeeklyReportInput.DailySource source, List<Finding> findings) { }
}
