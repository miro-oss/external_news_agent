package com.example.be.domain.reports.coverage;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceStatus;
import com.example.be.domain.analysis.relevance.TopicRelevanceStore;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.DailyReportSelector;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.TopicWeeklyReportSelector;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Copies report-period membership while the new report is reserved, before paid generation/selection. */
@Component
@RequiredArgsConstructor
public class ReportCoverageCapture {
    private final CollectionRunArticleRepository observations;
    private final IssueArticleRepository memberships;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final TopicRelevanceStore relevanceStore;

    public ReportCoverageSnapshot captureRun(NewsReport report, LocalDateTime now) {
        List<Finding> candidates = relevance.filterFindings(findings.findForReportByRunId(report.getRunId())).stream()
                .filter(ReportFindings::hasFullText).toList();
        return capture(report, observations.findReportCoverageByRunIds(List.of(report.getRunId())),
                candidates, null, now);
    }

    public ReportCoverageSnapshot captureDaily(NewsReport report, DailyReportSelector.Prepared prepared,
                                               LocalDateTime now) {
        // The legacy reservation overload has no detached generation input and must not invent one.
        if (prepared == null) return null;
        return capture(report, OracleInClause.batches(report.getSourceRunIds()).stream()
                        .flatMap(ids -> observations.findReportCoverageByRunIds(ids).stream()).toList(),
                prepared.candidates(), prepared.issues(), now);
    }

    public ReportCoverageSnapshot captureTopicWeek(NewsReport report,
            List<TopicWeeklyReportSelector.Prepared> prepared, LocalDateTime now) {
        var candidates = prepared.stream().flatMap(day -> day.candidates().stream()).distinct().toList();
        Map<Long, NewsIssue> byFinding = new LinkedHashMap<>();
        prepared.forEach(day -> day.issues().forEach(byFinding::putIfAbsent));
        return capture(report, observations.findTopicReportCoverage(report.getTopicId(),
                        report.getReportDate().atStartOfDay(), report.getReportEndDate().plusDays(1).atStartOfDay()),
                candidates, byFinding, now);
    }

    public ReportCoverageSnapshot mergeWeekly(NewsReport report, List<NewsReport> sources, LocalDateTime now) {
        // An incomplete historical denominator cannot safely be presented as a complete week's count.
        if (sources.stream().anyMatch(source -> source.getReportCoverageSnapshot() == null
                || !ReportCoverageSnapshot.RULE_VERSION.equals(source.getReportCoverageSnapshot().ruleVersion()))) return null;
        Set<IssueKey> suppressedPairs = new java.util.HashSet<>();
        for (NewsReport source : sources) {
            var sourceBindings = source.getReportCoverageSnapshot().bindings();
            Set<Long> bound = sourceBindings.stream().map(ReportCoverageSnapshot.Binding::findingId).collect(Collectors.toSet());
            // An exclusion with no original binding cannot safely be translated through current memberships.
            if (!bound.containsAll(source.getCompanySuppressedFindingIds())) return null;
            sourceBindings.stream().filter(binding -> source.getCompanySuppressedFindingIds().contains(binding.findingId()))
                    .forEach(binding -> suppressedPairs.add(new IssueKey(binding.issueId(), binding.topicId())));
        }
        Set<Long> allowed = Set.copyOf(report.getWeeklyInput().sourceFindingIds());
        var bindings = sources.stream().flatMap(source -> source.getReportCoverageSnapshot().bindings().stream())
                .distinct().toList();
        // Retain hidden source identities for future inherited reviews, without expanding counted pairs.
        Set<IssueKey> keys = bindings.stream().filter(binding -> allowed.contains(binding.findingId()))
                .map(binding -> new IssueKey(binding.issueId(), binding.topicId()))
                .collect(Collectors.toSet());
        var members = sources.stream().flatMap(source -> source.getReportCoverageSnapshot().observations().stream())
                .filter(member -> keys.contains(new IssueKey(member.issueId(), member.topicId())))
                .filter(member -> !suppressedPairs.contains(new IssueKey(member.issueId(), member.topicId())))
                .distinct().toList();
        return snapshot(report, now, members, bindings);
    }

    private ReportCoverageSnapshot capture(NewsReport report, List<CollectionRunArticle> observed,
            List<Finding> candidates, Map<Long, NewsIssue> preparedIssues, LocalDateTime now) {
        Set<Long> articleIds = observed.stream().map(row -> row.getArticle().getId()).collect(Collectors.toCollection(LinkedHashSet::new));
        candidates.forEach(finding -> articleIds.add(finding.getArticle().getId()));
        List<IssueArticle> links = OracleInClause.batches(articleIds).stream()
                .flatMap(ids -> memberships.findByArticleIds(ids).stream()).toList();
        Map<Long, List<IssueArticle>> byArticle = links.stream()
                .filter(link -> link.getIssue().getArticleCount() > 0)
                .collect(Collectors.groupingBy(link -> link.getArticle().getId()));
        var bindings = bindings(candidates, preparedIssues, byArticle);
        Map<Long, Set<TopicRelevanceGate.Key>> excluded = observed.stream().map(row -> row.getRun().getId()).distinct()
                .collect(Collectors.toMap(id -> id, relevance::excludedKeys));
        Map<LocalDate, Set<ArticleTopic>> dailyExcluded = latestDailyExclusions(report, observed);
        List<ReportCoverageSnapshot.Observation> copied = new ArrayList<>();
        for (CollectionRunArticle row : observed) {
            var article = row.getArticle();
            Long topicId = row.getTopic().getId();
            if (!article.hasFullText() || excluded.getOrDefault(row.getRun().getId(), Set.of())
                    .contains(new TopicRelevanceGate.Key(article.getId(), topicId))) continue;
            if (row.getRun().getStartedAt() != null && dailyExcluded.getOrDefault(row.getRun().getStartedAt().toLocalDate(), Set.of())
                    .contains(new ArticleTopic(article.getId(), topicId))) continue;
            for (IssueArticle link : byArticle.getOrDefault(article.getId(), List.of())) {
                if (!topicId.equals(link.getIssue().getTopic().getId())) continue;
                String publisher = publisher(article.getSourceName());
                copied.add(new ReportCoverageSnapshot.Observation(row.getId(), row.getRun().getId(), topicId,
                        link.getIssue().getId(), article.getId(), ReportPublisherIdentity.key(publisher, article.getCanonicalUrl()),
                        publisher, article.getTitle(), ReportCoverageService.safeUrl(article.getCanonicalUrl()), article.getPublishedAt(),
                        article.getContentGroup() == null ? null : article.getContentGroup().getId()));
            }
        }
        return snapshot(report, now, copied.stream().distinct().toList(), bindings);
    }

    private List<ReportCoverageSnapshot.Binding> bindings(List<Finding> candidates,
            Map<Long, NewsIssue> preparedIssues, Map<Long, List<IssueArticle>> byArticle) {
        Map<Long, Set<Long>> relevantTopics = preparedIssues == null ? relevance.relevantTopicIdsByFinding(candidates) : Map.of();
        List<ReportCoverageSnapshot.Binding> result = new ArrayList<>();
        for (Finding finding : candidates) {
            NewsIssue issue;
            if (preparedIssues != null) {
                issue = preparedIssues.get(finding.getId());
                // A merge between detached preparation and reservation must yield unavailable coverage,
                // rather than bind the old evidence to a replacement issue or claim a false zero.
                if (issue != null) {
                    Long preparedIssueId = issue.getId();
                    if (byArticle.getOrDefault(finding.getArticle().getId(), List.of()).stream()
                            .noneMatch(link -> preparedIssueId.equals(link.getIssue().getId()))) issue = null;
                }
            } else {
                Set<Long> topics = relevantTopics.getOrDefault(finding.getId(), Set.of(finding.getArticle().getTopic().getId()));
                issue = byArticle.getOrDefault(finding.getArticle().getId(), List.of()).stream().map(IssueArticle::getIssue)
                        .filter(value -> topics.contains(value.getTopic().getId())).min(Comparator.comparing(NewsIssue::getId)).orElse(null);
            }
            if (issue != null) result.add(new ReportCoverageSnapshot.Binding(finding.getId(), issue.getId(), issue.getTopic().getId()));
        }
        return result.stream().distinct().toList();
    }

    private Map<LocalDate, Set<ArticleTopic>> latestDailyExclusions(NewsReport report, List<CollectionRunArticle> observed) {
        if (report.getReportScope() == ReportScope.RUN) return Map.of();
        Map<LocalDate, List<Long>> idsByDate = observed.stream().filter(row -> row.getRun().getStartedAt() != null)
                .collect(Collectors.groupingBy(row -> row.getRun().getStartedAt().toLocalDate(),
                        Collectors.mapping(row -> row.getArticle().getId(), Collectors.toList())));
        Map<LocalDate, Set<ArticleTopic>> result = new LinkedHashMap<>();
        idsByDate.forEach((date, ids) -> result.put(date, relevanceStore.latestOnDate(date, ids.stream().distinct().toList()).stream()
                .filter(value -> value.status() != TopicRelevanceStatus.RELEVANT)
                .map(value -> new ArticleTopic(value.articleId(), value.topicId())).collect(Collectors.toSet())));
        return result;
    }

    static String publisher(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        String key = normalized.toLowerCase(Locale.ROOT).replace(" ", "");
        // These are platforms or missing-value markers, never verified publishers. No inferred aliases in v1.
        if (Set.of("", "-", "unknown", "null", "undefined", "n/a", "na", "미상", "매체미확인", "출처미상", "알수없음",
                "google", "googlenews", "google뉴스", "구글", "구글뉴스", "news.google.com", "naver", "navernews",
                "네이버", "네이버뉴스", "news.naver.com", "bing", "bingnews", "bing뉴스", "뉴스검색").contains(key)) return null;
        return normalized;
    }

    public static String periodLabel(NewsReport report) {
        if (report.getReportScope() == ReportScope.RUN) {
            return report.getRun() == null || report.getRun().getStartedAt() == null ? "해당 실행 수집 기준"
                    : report.getRun().getStartedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 수집 실행 기준 (한국시간)";
        }
        if (report.getReportScope() == ReportScope.DAILY) return report.getReportDate() + " 수집 기준 (한국시간)";
        return report.getReportDate() + " ~ " + report.getReportEndDate() + " 수집 기준 (한국시간)";
    }

    private ReportCoverageSnapshot snapshot(NewsReport report, LocalDateTime now,
            List<ReportCoverageSnapshot.Observation> members, List<ReportCoverageSnapshot.Binding> bindings) {
        return new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, now.atZone(ApiTimeZone.ZONE).toOffsetDateTime(),
                periodLabel(report), members, bindings);
    }

    private record ArticleTopic(Long articleId, Long topicId) { }
    private record IssueKey(Long issueId, Long topicId) { }
}
