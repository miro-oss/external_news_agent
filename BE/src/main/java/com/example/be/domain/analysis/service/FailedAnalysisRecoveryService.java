package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.quota.DuplicateQuotaReservationException;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit, bounded recovery. Never resets original reservations or retries an uncertain attempt implicitly. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FailedAnalysisRecoveryService {
    private final FailedAnalysisRecoverySnapshot snapshots;
    private final FindingRepository findings;
    private final AgentRunRepository attempts;
    private final ArticleAnalysisOrchestrator orchestrator;
    private final FindingReuseCache cache;
    private final FindingWriter writer;
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    public Plan plan(long runId, int maxArticles) {
        var snapshot = snapshots.load(runId, maxArticles);
        return new Plan(runId, snapshot.candidates().stream().map(candidate -> candidate.context().article().getId()).toList(),
                snapshot.skippedSuccessful(), snapshot.skippedUnavailable());
    }

    public Outcome recover(long runId, String recoveryId, int maxArticles) {
        AnalysisRecoveryKeys.validate(recoveryId);
        if (!active.add(runId)) throw new IllegalStateException("동일 수집 실행의 분석 복구가 진행 중입니다.");
        try { return recover(snapshots.load(runId, maxArticles), recoveryId); }
        finally { active.remove(runId); }
    }

    private Outcome recover(FailedAnalysisRecoverySnapshot.Snapshot snapshot, String recoveryId) {
        long runId = snapshot.runId();
        List<Long> recovered = new ArrayList<>(), failed = new ArrayList<>(), skipped = new ArrayList<>();
        for (var candidate : snapshot.candidates()) {
            long articleId = candidate.context().article().getId();
            var current = findings.findByRunIdAndArticleId(runId, articleId);
            if (current.isPresent() && current.orElseThrow().getAnalysisSource() != AnalysisSource.STUB) {
                skipped.add(articleId);
                continue;
            }
            String key = AnalysisRecoveryKeys.article(runId, articleId, recoveryId);
            // Even a failed/unknown prior attempt consumes this recovery ID. A new retry must be explicit.
            if (attempts.findByIdempotencyKey(key).isPresent()) {
                skipped.add(articleId);
                failed.add(articleId);
                break;
            }
            var input = candidate.context();
            var context = new AnalysisContext(runId, input.article(), input.plan(), IssueAnalysisContext.empty(),
                    input.selfCritiqueEligible(), input.topicOverride(), input.feedbackExamples(), recoveryId);
            try {
                var lookup = cache.lookupContexts(List.of(context), context.plan()).get(articleId);
                AnalysisResult result = lookup != null && lookup.cached().isPresent()
                        ? lookup.cached().orElseThrow() : orchestrator.analyze(context);
                if (!AnalysisSource.isLlmDerived(result.analysisSource())
                        || FindingKeyPoint.effectivePoints(result.keyPoints(), result.analysisSections()).stream()
                            .noneMatch(FindingEvidencePolicy::isSupported)) {
                    failed.add(articleId);
                    break;
                }
                boolean written = writer.recover(runId, articleId, candidate.changeType(), FindingReuseCache.inputHash(context), result);
                (written ? recovered : skipped).add(articleId);
            } catch (DuplicateQuotaReservationException duplicate) {
                skipped.add(articleId);
                failed.add(articleId);
                break;
            } catch (RuntimeException failure) {
                log.warn("선택 분석 복구 중단. runId={} articleId={} errorType={}", runId, articleId, failure.getClass().getSimpleName());
                failed.add(articleId);
                break;
            }
        }
        return new Outcome(runId, recoveryId, recovered, failed, skipped);
    }

    public record Plan(long runId, List<Long> articleIds, int skippedSuccessful, int skippedUnavailable) {
        public Plan { articleIds = List.copyOf(articleIds); }
    }
    public record Outcome(long runId, String recoveryId, List<Long> recoveredArticleIds,
                          List<Long> failedArticleIds, List<Long> skippedArticleIds) {
        public Outcome {
            recoveredArticleIds = List.copyOf(recoveredArticleIds);
            failedArticleIds = List.copyOf(failedArticleIds);
            skippedArticleIds = List.copyOf(skippedArticleIds);
        }
    }
}
