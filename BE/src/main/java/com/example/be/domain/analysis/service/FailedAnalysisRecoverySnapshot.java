package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.feedback.model.FeedbackModels.Category;
import com.example.be.domain.feedback.service.FeedbackLearningService;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Capture only saved run inputs in a short read transaction; never hold it across provider calls. */
@Component
@RequiredArgsConstructor
public class FailedAnalysisRecoverySnapshot {
    private final CollectionRunRepository runs;
    private final CollectionRunItemRepository items;
    private final CollectionRunArticleRepository observations;
    private final FindingRepository findings;
    private final AgentRunRepository attempts;
    private final TopicRelevancePolicy relevance;
    private final FeedbackLearningService feedback;

    @Transactional(readOnly = true)
    public Snapshot load(long runId, int maxArticles) {
        if (runId < 1 || maxArticles < 1 || maxArticles > 50)
            throw new IllegalArgumentException("복구 run ID와 기사 상한(1~50)을 확인해주세요.");
        CollectionRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("복구할 수집 실행이 없습니다."));
        if (run.getStatus() == null || run.getStatus().isInProgress() || run.getStartedAt() == null)
            throw new IllegalStateException("종료된 수집 실행만 복구할 수 있습니다.");
        Map<Long, Topic> topics = new LinkedHashMap<>();
        items.findExecutionItemsByRunId(runId).forEach(item -> {
            if (item.getTopicSnapshot() != null)
                topics.putIfAbsent(item.getTopicSnapshot().topicId(), item.collectionTopic());
        });
        Map<Long, Finding> existing = findings.findForReportByRunId(runId).stream()
                .collect(Collectors.toMap(finding -> finding.getArticle().getId(), Function.identity()));
        var observed = observations.findReportCoverageByRunIds(List.of(runId));
        var excluded = relevance.excludedKeys(runId);
        List<Candidate> candidates = new ArrayList<>();
        int successful = 0, unavailable = 0;
        for (Long articleId : attempts.findFailedAnalysisArticleIds(runId)) {
            Finding previous = existing.get(articleId);
            if (previous != null && AnalysisSource.isLlmDerived(previous.getAnalysisSource())) {
                successful++;
                continue;
            }
            // A null/legacy source is not evidence of a failed STUB and must not be overwritten.
            if (previous == null || previous.getAnalysisSource() != AnalysisSource.STUB
                    || previous.getAnalysisInputHash() == null) {
                unavailable++;
                continue;
            }
            var matchingObservations = observed.stream().filter(row -> articleId.equals(row.getArticle().getId()))
                    .filter(row -> row.getTopic() != null && topics.containsKey(row.getTopic().getId()))
                    .filter(row -> !excluded.contains(new TopicRelevanceGate.Key(articleId, row.getTopic().getId())))
                    .filter(row -> row.getArticle().hasFullText()).toList();
            AnalysisContext matched = null;
            for (var observation : matchingObservations) {
                var article = observation.getArticle();
                var topic = topics.get(observation.getTopic().getId());
                // Resolve the provider's publisher fallback while the source relationship is attached.
                if (article.getSource() != null) article.getSource().getName();
                var examples = feedback.forTopic(topic, run.getStartedAt(), Set.of(Category.SUMMARY_ERROR, Category.OTHER));
                var context = new AnalysisContext(runId, article, run.getLlmPlan(),
                        IssueAnalysisContext.empty(), false, topic, examples);
                if (Objects.equals(previous.getAnalysisInputHash(), FindingReuseCache.inputHash(context))) {
                    matched = context;
                    break;
                }
                // Eligibility is hashed even though recovery skips this optional step.
                var eligible = new AnalysisContext(runId, article, run.getLlmPlan(),
                        IssueAnalysisContext.empty(), true, topic, examples);
                if (Objects.equals(previous.getAnalysisInputHash(), FindingReuseCache.inputHash(eligible))) {
                    matched = eligible;
                    break;
                }
            }
            if (matched == null) { unavailable++; continue; }
            candidates.add(new Candidate(matched, previous.getChangeType()));
        }
        if (candidates.size() > maxArticles)
            throw new IllegalStateException("복구 대상 " + candidates.size() + "건이 지정 상한 " + maxArticles + "건을 초과합니다.");
        return new Snapshot(runId, candidates, successful, unavailable);
    }

    public record Candidate(AnalysisContext context, ChangeType changeType) { }
    public record Snapshot(long runId, List<Candidate> candidates, int skippedSuccessful, int skippedUnavailable) {
        public Snapshot { candidates = List.copyOf(candidates); }
    }
}
