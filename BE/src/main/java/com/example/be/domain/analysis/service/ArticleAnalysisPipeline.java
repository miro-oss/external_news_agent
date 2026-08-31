package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 외부 모델 호출 자리가 생겨도 DB 트랜잭션을 잡지 않도록 분석과 저장을 분리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleAnalysisPipeline {

    private final CollectionRunArticleRepository runArticleRepository;
    private final CollectionRunRepository runRepository;
    private final ArticleAnalysisOrchestrator orchestrator;
    private final FindingReuseCache reuseCache;
    private final FindingWriter findingWriter;

    public void analyze(Long runId) {
        analyze(runId, Set.of());
    }

    public void analyze(Long runId, Set<Long> refreshedArticleIds) {
        AgentPlan plan = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("분석할 수집 실행이 없습니다. runId=" + runId))
                .getLlmPlan();
        List<Target> targets = targets(runId, refreshedArticleIds);
        Map<Long, FindingReuseCache.Lookup> lookups = cacheLookups(targets, plan);
        for (Target target : targets) {
            try {
                FindingReuseCache.Lookup lookup = lookups.get(target.article().getId());
                if (lookup.cached().isPresent()) {
                    findingWriter.write(runId, target.article().getId(), target.changeType(),
                            lookup.analysisInputHash(), lookup.cached().orElseThrow());
                    continue;
                }
                AnalysisResult result = orchestrator.analyze(new AnalysisContext(runId, target.article(), plan));
                findingWriter.write(runId, target.article().getId(), target.changeType(),
                        lookup.analysisInputHash(), result);
            } catch (RuntimeException exception) {
                log.warn("기사 분석에 실패했다. runId={} articleId={} error={}",
                        runId, target.article().getId(), exception.getMessage(), exception);
                findingWriter.addFailureWarning(runId, target.article().getId(), messageOf(exception));
            }
        }
    }

    private Map<Long, FindingReuseCache.Lookup> cacheLookups(List<Target> targets, AgentPlan plan) {
        try {
            return reuseCache.lookupAll(targets.stream().map(Target::article).toList(), plan);
        } catch (RuntimeException exception) {
            log.warn("finding 재사용 캐시 조회에 실패해 새로 분석한다. error={}", exception.getMessage());
            Map<Long, FindingReuseCache.Lookup> misses = new LinkedHashMap<>();
            targets.forEach(target -> misses.put(
                    target.article().getId(),
                    new FindingReuseCache.Lookup(
                            FindingReuseCache.inputHash(target.article()), Optional.empty())));
            return Map.copyOf(misses);
        }
    }

    private List<Target> targets(Long runId, Set<Long> refreshedArticleIds) {
        Map<Long, Target> byArticleId = new LinkedHashMap<>();
        for (CollectionRunArticle observation :
                runArticleRepository.findRepresentativeAnalysisTargetsByRunId(runId)) {
            addTarget(byArticleId, observation, observation.getChangeType());
        }
        if (!refreshedArticleIds.isEmpty()) {
            for (CollectionRunArticle observation :
                    runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
                            runId, refreshedArticleIds)) {
                // 메타데이터는 그대로여도 새 전문을 확보했으므로 분석 결과 관점에서는 UPDATED다.
                ChangeType changeType = observation.getChangeType() == ChangeType.UNCHANGED
                        ? ChangeType.UPDATED
                        : observation.getChangeType();
                addTarget(byArticleId, observation, changeType);
            }
        }
        return byArticleId.values().stream().filter(this::isReady).toList();
    }

    private void addTarget(Map<Long, Target> byArticleId,
                           CollectionRunArticle observation,
                           ChangeType changeType) {
        Target candidate = new Target(observation.getArticle(), changeType);
        byArticleId.merge(observation.getArticle().getId(), candidate, this::preferUpdated);
    }

    private boolean isReady(Target target) {
        Article article = target.article();
        // UPDATED 재수집 실패 시 보존 중인 옛 전문과 새 메타데이터를 섞어 분석하지 않는다.
        return !StringUtils.hasText(article.getBody()) || article.getFetchStatus() == FetchStatus.FULLTEXT;
    }

    private Target preferUpdated(Target left, Target right) {
        return left.changeType() == ChangeType.UPDATED ? left : right;
    }

    private String messageOf(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record Target(Article article, ChangeType changeType) {
    }
}
