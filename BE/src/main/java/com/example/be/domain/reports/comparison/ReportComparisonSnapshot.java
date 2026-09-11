package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Detached generation input, never reconstructed from a current finding or article on a later read. */
public record ReportComparisonSnapshot(int version, List<Issue> issues, List<ReportCollectionContext> scopes) {
    public ReportComparisonSnapshot { issues = List.copyOf(issues); scopes = List.copyOf(scopes); }
    public record Issue(long findingId, String analysisInputHash, String promptVersion,
                        String analyzedAt, ReportChanges.Side side) { }

    public static ReportComparisonSnapshot capture(List<Finding> findings, Map<Long, NewsIssue> issueByArticle) {
        List<Issue> issues = new ArrayList<>();
        for (Finding finding : findings) {
            NewsIssue issue = issueByArticle.get(finding.getArticle().getId());
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
            var side = new ReportChanges.Side(issue.getId(), issue.getTopic().getId(),
                    finding.getArticle().getTitle(), points.isEmpty() ? "" : points.getFirst().text(), claims);
            issues.add(new Issue(finding.getId(), finding.getAnalysisInputHash(), finding.getPromptVersion(),
                    Objects.toString(finding.getAnalyzedAt(), null), side));
        }
        return new ReportComparisonSnapshot(1, issues, List.of());
    }

    public ReportComparisonSnapshot withScopes(List<ReportCollectionContext> value) {
        return new ReportComparisonSnapshot(version, issues, value);
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
                }).toList(), scopes);
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
                                  Set<String> excluded, int batchSize, int intervalMinutes) {
        static ScopeConditions from(CollectionTopicSnapshot topic) {
            // Query is passed to connectors verbatim after outer trimming; preserve interior quoted whitespace.
            return new ScopeConditions(topic.queryText() == null ? "" : topic.queryText().trim(),
                    keywords(topic.requiredKeywords()), keywords(topic.optionalKeywords()),
                    keywords(topic.excludedKeywords()), topic.batchSize(), topic.intervalMinutes());
        }
        private static Set<String> keywords(List<String> values) {
            return values.stream().map(value -> value.trim().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        }
    }
}
