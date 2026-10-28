package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;

/** Detached generation input, never reconstructed from a current finding or article on a later read. */
public record ReportComparisonSnapshot(int version, List<Issue> issues, List<ReportCollectionContext> scopes,
                                       Period period, List<SourceCondition> sourceConditions) {
    public ReportComparisonSnapshot {
        issues = List.copyOf(issues); scopes = List.copyOf(scopes);
        sourceConditions = sourceConditions == null ? null : List.copyOf(sourceConditions);
    }
    public ReportComparisonSnapshot(int version, List<Issue> issues, List<ReportCollectionContext> scopes) {
        this(version, issues, scopes, null, null);
    }
    /** A RUN is one observation, not a made-up collection window or its execution duration. */
    public record Period(ReportScope scope, Long topicId, LocalDate from, LocalDate through,
                         Integer durationDays, Long runId, LocalDateTime runStartedAt, List<LocalDate> missingDates) {
        public Period { missingDates = missingDates == null ? List.of() : List.copyOf(missingDates); }
        public static Period from(NewsReport report) {
            if (report.getReportScope() == ReportScope.RUN) {
                var run = report.getRun();
                var started = run == null ? null : run.getStartedAt();
                return new Period(ReportScope.RUN, null, started == null ? null : started.toLocalDate(),
                        started == null ? null : started.toLocalDate(), null, report.getRunId(), started, List.of());
            }
            LocalDate from = report.getReportDate();
            LocalDate through = report.getReportScope() == ReportScope.DAILY ? from : report.getReportEndDate();
            Integer days = from == null || through == null || through.isBefore(from) ? null
                    : Math.toIntExact(ChronoUnit.DAYS.between(from, through) + 1);
            return new Period(report.getReportScope(), report.getTopicId(), from, through, days, null, null,
                    report.getMissingReportDates());
        }
    }
    public record SourceCondition(Long runId, Long topicId, Long sourceId, String fingerprint, boolean complete) { }

    public static List<SourceCondition> sourceConditions(List<CollectionRun> runs, Long topicId) {
        return runs.stream().flatMap(run -> run.getItems().stream()
                .filter(item -> topicId == null || item.getTopicSnapshot() != null
                        && topicId.equals(item.getTopicSnapshot().topicId())
                        // A legacy same-topic execution is an uncertainty boundary, not evidence of known conditions.
                        || item.getTopicSnapshot() == null && (item.getTopic() == null || topicId.equals(item.getTopic().getId())))
                .map(item -> new SourceCondition(run.getId(), item.getTopicSnapshot() == null ? null : item.getTopicSnapshot().topicId(),
                        item.getSource() == null ? null : item.getSource().getId(),
                        item.hasComparableSourceConfiguration() ? item.getComparisonSourceFingerprint() : null,
                        item.getStatus() == RunItemStatus.SUCCESS))).distinct().toList();
    }
    public record Issue(long findingId, String analysisInputHash, String promptVersion,
                        String analyzedAt, ReportChanges.Side side) { }

    public static ReportComparisonSnapshot capture(List<Finding> findings, Map<Long, NewsIssue> issueByArticle) {
        return capture(findings, finding -> identity(issueByArticle.get(finding.getArticle().getId())));
    }

    /** A shared article may be analyzed for different accepted topics in different runs. */
    public static ReportComparisonSnapshot captureByFinding(List<Finding> findings, Map<Long, NewsIssue> issueByFinding) {
        return capture(findings, finding -> identity(issueByFinding.get(finding.getId())));
    }

    public static ReportComparisonSnapshot captureByBindings(List<Finding> findings, List<ReportCoverageSnapshot.Binding> bindings) {
        Map<Long, List<ReportCoverageSnapshot.Binding>> byFinding = bindings.stream()
                .collect(Collectors.groupingBy(ReportCoverageSnapshot.Binding::findingId));
        return capture(findings, finding -> {
            var ids = byFinding.getOrDefault(finding.getId(), List.of()).stream()
                    .map(binding -> new Identity(binding.issueId(), binding.topicId())).distinct().toList();
            return ids.size() == 1 ? ids.getFirst() : null;
        });
    }
    private record Identity(long issueId, long topicId) { }
    private static Identity identity(NewsIssue issue) {
        return issue == null ? null : new Identity(issue.getId(), issue.getTopic().getId());
    }
    private static ReportComparisonSnapshot capture(List<Finding> findings, Function<Finding, Identity> resolveIssue) {
        List<Issue> issues = new ArrayList<>();
        for (Finding finding : findings) {
            Identity issue = resolveIssue.apply(finding);
            if (issue == null) throw new IllegalStateException("선택된 이슈 식별자가 없습니다.");
            Map<Integer, String> sentences = (finding.getSections() == null ? List.<FindingSection>of()
                    : finding.getSections()).stream().collect(Collectors.toMap(FindingSection::index,
                    FindingSection::text, (first, second) -> first));
            List<ReportChanges.Claim> claims = new ArrayList<>();
            // Preserve the full selected input for either the compact Agent or the full safe fallback.
            var points = FindingEvidencePolicy.supportedKeyPoints(finding);
            for (int index = 0; index < points.size(); index++) {
                var point = points.get(index);
                var evidence = point.evidence().stream().distinct()
                        .filter(id -> sentences.get(id) != null && !sentences.get(id).isBlank())
                        .map(id -> new ReportChanges.Evidence(finding.getId(), finding.getArticle().getId(),
                                finding.getRun().getId(), finding.getArticle().getTitle(),
                                finding.getArticle().getCanonicalUrl(), id, sentences.get(id))).toList();
                if (evidence.size() != point.evidence().stream().distinct().count()) {
                    throw new IllegalStateException("생성 입력의 근거 문장을 보존할 수 없습니다.");
                }
                claims.add(new ReportChanges.Claim("finding-" + finding.getId() + "-claim-" + index,
                        point.text(), evidence));
            }
            var side = new ReportChanges.Side(issue.issueId(), issue.topicId(),
                    finding.getArticle().getTitle(), points.isEmpty() ? "" : points.getFirst().text(), claims);
            issues.add(new Issue(finding.getId(), finding.getAnalysisInputHash(), finding.getPromptVersion(),
                    Objects.toString(finding.getAnalyzedAt(), null), side));
        }
        return new ReportComparisonSnapshot(1, issues, List.of());
    }

    public ReportComparisonSnapshot withScopes(List<ReportCollectionContext> value) {
        return new ReportComparisonSnapshot(version, issues, value, period, sourceConditions);
    }

    public ReportComparisonSnapshot withReportContext(NewsReport report) {
        return new ReportComparisonSnapshot(version, issues, scopes.isEmpty() ? report.getCollectionContexts() : scopes,
                period == null ? Period.from(report) : period, sourceConditions);
    }
    public ReportComparisonSnapshot withSourceConditions(List<SourceCondition> values) {
        return new ReportComparisonSnapshot(version, issues, scopes, period, values);
    }
    /** Select after reflected() so an unselected newer observation cannot replace the final report evidence. */
    public ReportComparisonSnapshot latestPerIssue() {
        Map<Identity, Issue> latest = new LinkedHashMap<>();
        // Captures append DAILY sources in reportDate order; later observations replace earlier ones.
        issues.forEach(issue -> latest.put(new Identity(issue.side().issueId(), issue.side().topicId()), issue));
        return new ReportComparisonSnapshot(version, List.copyOf(latest.values()), scopes, period, sourceConditions);
    }

    /** Coverage is immutable once the report completes. Missing inputs are not fabricated. */
    public ReportComparisonSnapshot reflected(List<Long> findingIds) {
        return reflected(findingIds, false);
    }

    public ReportComparisonSnapshot reflected(List<Long> findingIds, boolean compact) {
        Set<Long> ids = Set.copyOf(findingIds);
        if (!issues.stream().map(Issue::findingId).collect(Collectors.toSet()).containsAll(ids)) {
            throw new IllegalStateException("완료된 보고서의 입력 스냅샷이 불완전합니다.");
        }
        return new ReportComparisonSnapshot(version,
                issues.stream().filter(issue -> ids.contains(issue.findingId())).map(issue -> {
                    if (!compact) return issue;
                    var side = issue.side();
                    return new Issue(issue.findingId(), issue.analysisInputHash(), issue.promptVersion(), issue.analyzedAt(),
                            new ReportChanges.Side(side.issueId(), side.topicId(), side.title(), side.summary(),
                                    side.claims().stream().limit(3).toList()));
                }).toList(), scopes, period, sourceConditions);
    }

    public boolean hasKnownScopes() {
        return version == 1 && !scopes.isEmpty() && scopes.stream().allMatch(scope -> !scope.topics().isEmpty())
                && issues.stream().allMatch(issue -> scopes.stream().flatMap(scope -> scope.topics().stream())
                .anyMatch(topic -> topic.topicId() == issue.side().topicId()));
    }

    public Map<Long, Set<ScopeConditions>> scopesByTopic() {
        return scopes.stream().flatMap(scope -> scope.topics().stream())
                .collect(Collectors.groupingBy(CollectionTopicSnapshot::topicId,
                        Collectors.mapping(ScopeConditions::from, Collectors.toSet())));
    }

    /** Match actual TopicKeywordFilter semantics; display labels and keyword order do not alter collection. */
    public record ScopeConditions(String queryText, Set<String> required, Set<String> optional,
                                  Set<String> excluded, int batchSize, int intervalMinutes, String dartCorpCode) {
        static ScopeConditions from(CollectionTopicSnapshot topic) {
            // Query is passed to connectors verbatim after outer trimming; preserve interior quoted whitespace.
            return new ScopeConditions(topic.queryText() == null ? "" : topic.queryText().trim(),
                    keywords(topic.requiredKeywords()), keywords(topic.optionalKeywords()),
                    keywords(topic.excludedKeywords()), topic.batchSize(), topic.intervalMinutes(),
                    topic.dartCompany() == null ? null : topic.dartCompany().corpCode());
        }
        private static Set<String> keywords(List<String> values) {
            return values.stream().map(value -> value.trim().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        }
    }
}
