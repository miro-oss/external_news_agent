package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 외부 모델 호출 자리가 생겨도 DB 트랜잭션을 잡지 않도록 분석과 저장을 분리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleAnalysisPipeline {

    private final CollectionRunArticleRepository runArticleRepository;
    private final CollectionRunRepository runRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final ArticleAnalysisOrchestrator orchestrator;
    private final FindingReuseCache reuseCache;
    private final FindingWriter findingWriter;
    private final AnalysisSelectionProperties selectionProperties;
    private final TopicFitScorer topicFitScorer;

    public void analyze(Long runId) {
        analyze(runId, Set.of());
    }

    public void analyze(Long runId, Set<Long> refreshedArticleIds) {
        analyze(runId, refreshedArticleIds, true);
    }

    public void analyzeWithoutClustering(Long runId, Set<Long> refreshedArticleIds) {
        analyze(runId, refreshedArticleIds, false);
    }

    /** 조사 액션으로 새 전문이나 이슈 멤버가 생긴 범위만 다시 분석한다. */
    public void analyzeInvestigation(Long runId, Set<Long> refreshedArticleIds) {
        if (refreshedArticleIds.isEmpty()) {
            return;
        }
        AgentPlan plan = plan(runId);
        analyzeTargets(runId, investigationTargets(runId, refreshedArticleIds), plan, true, true);
    }

    private void analyze(Long runId, Set<Long> refreshedArticleIds, boolean clustered) {
        AgentPlan plan = plan(runId);
        List<Target> targets = targets(runId, refreshedArticleIds, clustered);
        // coverage의 분모는 이슈다. 클러스터링 실패 시 기사 단위 degrade 결과를 이슈 수로 가장하지 않는다.
        findingWriter.recordTargetCount(runId, clustered ? targets.size() : 0);
        analyzeTargets(runId, targets, plan, clustered, false);
    }

    private AgentPlan plan(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("분석할 수집 실행이 없습니다. runId=" + runId))
                .getLlmPlan();
    }

    private void analyzeTargets(Long runId,
                                List<Target> targets,
                                AgentPlan plan,
                                boolean clustered,
                                boolean refreshExisting) {
        Map<Long, AnalysisContext> contexts = analysisContexts(runId, targets, plan, clustered);
        Map<Long, FindingReuseCache.Lookup> lookups = cacheLookups(contexts.values().stream().toList(), plan);
        for (Target target : targets) {
            try {
                AnalysisContext context = contexts.get(target.article().getId());
                FindingReuseCache.Lookup lookup = lookups.get(target.article().getId());
                if (lookup.cached().isPresent()) {
                    writeFinding(runId, target, lookup.analysisInputHash(),
                            lookup.cached().orElseThrow(), refreshExisting);
                    continue;
                }
                AnalysisResult result = orchestrator.analyze(context);
                writeFinding(runId, target, lookup.analysisInputHash(), result, refreshExisting);
            } catch (RuntimeException exception) {
                log.warn("기사 분석에 실패했다. runId={} articleId={} error={}",
                        runId, target.article().getId(), exception.getMessage(), exception);
                findingWriter.addFailureWarning(runId, target.article().getId(), messageOf(exception));
            }
        }
    }

    private void writeFinding(Long runId,
                              Target target,
                              String analysisInputHash,
                              AnalysisResult result,
                              boolean refreshExisting) {
        if (refreshExisting) {
            findingWriter.refresh(
                    runId, target.article().getId(), target.changeType(), analysisInputHash, result);
        } else {
            findingWriter.write(
                    runId, target.article().getId(), target.changeType(), analysisInputHash, result);
        }
    }

    private Map<Long, FindingReuseCache.Lookup> cacheLookups(List<AnalysisContext> contexts, AgentPlan plan) {
        try {
            return reuseCache.lookupContexts(contexts, plan);
        } catch (RuntimeException exception) {
            log.warn("finding 재사용 캐시 조회에 실패해 새로 분석한다. error={}", exception.getMessage());
            Map<Long, FindingReuseCache.Lookup> misses = new LinkedHashMap<>();
            contexts.forEach(context -> misses.put(
                    context.article().getId(),
                    new FindingReuseCache.Lookup(
                            FindingReuseCache.inputHash(context), Optional.empty())));
            return Map.copyOf(misses);
        }
    }

    private Map<Long, AnalysisContext> analysisContexts(Long runId,
                                                        List<Target> targets,
                                                        AgentPlan plan,
                                                        boolean clustered) {
        Map<Long, IssueAnalysisContext> issues = clustered
                ? issueContexts(targets)
                : Map.of();
        Set<Long> selfCritiqueTargets = selfCritiqueTargets(targets, issues);
        Map<Long, AnalysisContext> contexts = new LinkedHashMap<>();
        targets.forEach(target -> contexts.put(
                target.article().getId(),
                new AnalysisContext(
                        runId,
                        target.article(),
                        plan,
                        issues.getOrDefault(
                                target.article().getId(), IssueAnalysisContext.empty()),
                        selfCritiqueTargets.contains(target.article().getId()))));
        return Map.copyOf(contexts);
    }

    private Set<Long> selfCritiqueTargets(List<Target> targets,
                                          Map<Long, IssueAnalysisContext> issues) {
        int limit = (int) Math.floor(issues.size() * 0.2d);
        if (limit == 0 || issues.isEmpty()) {
            return Set.of();
        }
        return targets.stream()
                .filter(target -> issues.containsKey(target.article().getId()))
                .sorted(Comparator.<Target, BigDecimal>comparing(
                                target -> importanceScore(target, issues),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingDouble(Target::topicFit).reversed())
                        .thenComparing(
                                target -> distinctPublisherCount(
                                        issues.get(target.article().getId())),
                                Comparator.reverseOrder())
                        .thenComparing(
                                target -> target.article().getPublishedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(target -> target.article().getId()))
                .limit(limit)
                .map(target -> target.article().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal importanceScore(Target target,
                                       Map<Long, IssueAnalysisContext> issues) {
        return issues.get(target.article().getId()).importanceScore();
    }

    private long distinctPublisherCount(IssueAnalysisContext issue) {
        return issue.articles().stream()
                .map(article -> StringUtils.hasText(article.getSourceName())
                        ? article.getSourceName().trim()
                        : article.getSource() == null ? null : article.getSource().getName())
                .filter(StringUtils::hasText)
                .distinct()
                .count();
    }

    private Map<Long, IssueAnalysisContext> issueContexts(List<Target> targets) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Set<Long> representativeIds = targets.stream()
                .map(target -> target.article().getId())
                .collect(Collectors.toSet());
        List<IssueArticle> memberships =
                issueArticleRepository.findIssueContextsByRepresentativeArticleIds(representativeIds);
        Map<Long, List<IssueArticle>> byIssue = memberships.stream()
                .collect(Collectors.groupingBy(
                        membership -> membership.getIssue().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, IssueAnalysisContext> result = new LinkedHashMap<>();
        byIssue.forEach((issueId, issueMemberships) -> issueMemberships.stream()
                .filter(membership -> membership.getRole() == IssueArticleRole.REPRESENTATIVE)
                .filter(membership -> representativeIds.contains(membership.getArticle().getId()))
                .findFirst()
                .ifPresent(representative -> result.putIfAbsent(
                        representative.getArticle().getId(),
                        new IssueAnalysisContext(
                                issueId,
                                representative.getArticle().getId(),
                                issueMemberships.stream().map(IssueArticle::getArticle).toList(),
                                representativeIds,
                                representative.getIssue().getImportanceScore()))));
        return Map.copyOf(result);
    }

    private List<Target> targets(Long runId, Set<Long> refreshedArticleIds, boolean clustered) {
        Map<Long, Target> byArticleId = new LinkedHashMap<>();
        if (!clustered) {
            addUnclusteredTargets(byArticleId, runId, refreshedArticleIds);
            return prioritized(byArticleId.values());
        }
        for (CollectionRunArticle observation :
                runArticleRepository.findRepresentativeAnalysisTargetsByRunId(runId)) {
            addTarget(byArticleId, observation, observation.getChangeType());
        }
        addMissingRepresentatives(byArticleId, issueArticleRepository.findRepresentativesForRun(runId));
        if (!refreshedArticleIds.isEmpty()) {
            for (List<Long> articleIds : OracleInClause.batches(refreshedArticleIds)) {
                for (CollectionRunArticle observation :
                        runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
                                runId, articleIds)) {
                    // 메타데이터는 그대로여도 새 전문을 확보했으므로 분석 결과 관점에서는 UPDATED다.
                    ChangeType changeType = observation.getChangeType() == ChangeType.UNCHANGED
                            ? ChangeType.UPDATED
                            : observation.getChangeType();
                    addTarget(byArticleId, observation, changeType);
                }
                addMissingRepresentatives(byArticleId,
                        issueArticleRepository.findRepresentativesForRunAndObservedArticleIdIn(
                                runId, articleIds));
            }
        }
        return prioritized(byArticleId.values());
    }

    private List<Target> investigationTargets(Long runId, Set<Long> refreshedArticleIds) {
        Map<Long, Target> byArticleId = new LinkedHashMap<>();
        for (List<Long> articleIds : OracleInClause.batches(refreshedArticleIds)) {
            for (CollectionRunArticle observation :
                    runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
                            runId, articleIds)) {
                addTarget(byArticleId, observation, ChangeType.UPDATED);
            }
            addMissingRepresentatives(byArticleId,
                    issueArticleRepository.findRepresentativesForRunAndObservedArticleIdIn(
                            runId, articleIds));
        }
        return prioritized(byArticleId.values());
    }

    private void addMissingRepresentatives(Map<Long, Target> targets, List<IssueArticle> memberships) {
        if (memberships == null) {
            return;
        }
        memberships.forEach(membership -> {
            Article article = membership.getArticle();
            Topic topic = membership.getIssue() == null
                    ? article.getTopic()
                    : membership.getIssue().getTopic();
            targets.merge(
                    article.getId(),
                    new Target(article, ChangeType.UPDATED, topicFit(topic, article)),
                    this::preserveObservedChangeType);
        });
    }

    private void addUnclusteredTargets(Map<Long, Target> targets,
                                       Long runId,
                                       Set<Long> refreshedArticleIds) {
        runArticleRepository.findUnclusteredAnalysisTargetsByRunId(runId)
                .forEach(observation -> addTarget(targets, observation, observation.getChangeType()));
        if (!refreshedArticleIds.isEmpty()) {
            for (List<Long> articleIds : OracleInClause.batches(refreshedArticleIds)) {
                runArticleRepository.findUnclusteredAnalysisTargetsByRunIdAndArticleIdIn(runId, articleIds)
                        .forEach(observation -> addTarget(
                                targets,
                                observation,
                                observation.getChangeType() == ChangeType.UNCHANGED
                                        ? ChangeType.UPDATED
                                        : observation.getChangeType()));
            }
        }
    }

    private void addTarget(Map<Long, Target> byArticleId,
                           CollectionRunArticle observation,
                           ChangeType changeType) {
        Target candidate = new Target(
                observation.getArticle(),
                changeType,
                topicFit(observation.getTopic(), observation.getArticle()));
        byArticleId.merge(observation.getArticle().getId(), candidate, this::preferUpdated);
    }

    private List<Target> prioritized(Collection<Target> targets) {
        List<Target> ordered = targets.stream()
                .filter(this::isReady)
                .sorted(Comparator.comparingDouble(Target::topicFit).reversed()
                        .thenComparing(
                                target -> target.article().getPublishedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(target -> target.article().getId()))
                .toList();
        int limit = selectionProperties.getIssueLimitPerRun();
        if (ordered.size() > limit) {
            log.info("분석 상한으로 이슈 대표를 선별한다. candidates={} selected={}", ordered.size(), limit);
        }
        return ordered.stream().limit(limit).toList();
    }

    private double topicFit(Topic topic, Article article) {
        return topicFitScorer.score(
                topic,
                article.getTitle(),
                article.getSummary(),
                article.getLanguage(),
                article.getSource() == null ? null : article.getSource().getLanguage());
    }

    private boolean isReady(Target target) {
        Article article = target.article();
        // UPDATED 재수집 실패 시 보존 중인 옛 전문과 새 메타데이터를 섞어 분석하지 않는다.
        return !StringUtils.hasText(article.getBody()) || article.getFetchStatus() == FetchStatus.FULLTEXT;
    }

    private Target preferUpdated(Target left, Target right) {
        ChangeType changeType = left.changeType() == ChangeType.UPDATED
                || right.changeType() == ChangeType.UPDATED
                ? ChangeType.UPDATED
                : right.changeType();
        return new Target(left.article(), changeType, Math.max(left.topicFit(), right.topicFit()));
    }

    /** 이슈 대표 보충은 topicFit만 보강하며, 이번 run에서 직접 관측한 NEW/UPDATED 판정은 바꾸지 않는다. */
    private Target preserveObservedChangeType(Target existing, Target representative) {
        return new Target(
                existing.article(),
                existing.changeType(),
                Math.max(existing.topicFit(), representative.topicFit()));
    }

    private String messageOf(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record Target(Article article, ChangeType changeType, double topicFit) {
    }
}
