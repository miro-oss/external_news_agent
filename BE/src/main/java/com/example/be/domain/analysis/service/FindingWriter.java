package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FindingWriter {

    public static final String CODE_ANALYSIS_FAILED = "ANALYSIS_FAILED";

    private final FindingRepository findingRepository;
    private final CollectionRunRepository runRepository;
    private final ArticleRepository articleRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final NewsWatchRepository watchRepository;
    private final SensitivityCalculator sensitivityCalculator;
    private final BreakingNewsDetector breakingNewsDetector;

    private static final long HIGH_SENSITIVITY_WATCH_HOURS = 48;

    @Transactional
    public void recordTargetCount(Long runId, int targetCount) {
        runRepository.findById(runId).orElseThrow().recordAnalysisTargetIssueCount(targetCount);
    }

    @Transactional
    public void write(Long runId,
                      Long articleId,
                      ChangeType changeType,
                      String analysisInputHash,
                      AnalysisResult result) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        // 동일 기사 행을 잠근 뒤 존재 여부를 확인해 동시 호출도 하나의 finding만 남긴다.
        Article article = articleRepository.findByIdForUpdate(articleId).orElseThrow();
        if (findingRepository.existsByRunIdAndArticleId(runId, articleId)) {
            return;
        }

        findingRepository.save(Finding.builder()
                .run(run)
                .article(article)
                .changeType(changeType)
                .summary(result.summary())
                // 구조화 section이 없는 Stub/레거시 결과만 기존 key_points 컬럼을 사용한다.
                .keyPoints(result.analysisSections().isEmpty() ? result.keyPoints() : List.of())
                .intent(result.intent())
                .sentiment(result.sentiment())
                .sensitivity(result.sensitivity())
                .relevance(result.relevance())
                .category(result.category())
                .analysisSource(result.analysisSource())
                .sections(result.sections())
                .analysisSections(result.analysisSections())
                .entities(result.entities())
                .perspectiveTags(result.perspectiveTags())
                .promptVersion(result.metadata().promptVersion())
                .llmProvider(result.metadata().provider())
                .llmModel(result.metadata().model())
                .inputTokens(result.metadata().inputTokens())
                .outputTokens(result.metadata().outputTokens())
                .costUsd(result.metadata().costUsd())
                .credits(result.metadata().credits())
                .analysisInputHash(analysisInputHash)
                .inputTruncated(result.metadata().truncated())
                .analyzedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        issueArticleRepository.findByArticleIdOrderByIssueIdAsc(articleId).forEach(membership -> {
            if (membership.getRole() == IssueArticleRole.REPRESENTATIVE) {
                membership.getIssue().applyRepresentativeSummary(result.summary());
                membership.getIssue().applyRepresentativeSensitivity(result.sensitivity().getScore());
            }
            if (breakingNewsDetector.hasExplicitMarker(article.getTitle())
                    && sensitivityCalculator.isHigh(result.sensitivity().getScore())) {
                LocalDateTime expiresAt = now.plusHours(HIGH_SENSITIVITY_WATCH_HOURS);
                watchRepository.findByIssueIdAndWatchType(
                                membership.getIssue().getId(), WatchType.HIGH_SENSITIVITY)
                        .ifPresentOrElse(
                                watch -> {
                                    if (!watch.isActive() || !watch.getExpiresAt().isAfter(now)) {
                                        watch.renewHighSensitivity(
                                                expiresAt, result.sensitivity().getScore());
                                    } else {
                                        watch.extendHighSensitivityUntil(
                                                expiresAt, result.sensitivity().getScore());
                                    }
                                },
                                () -> watchRepository.save(NewsWatch.builder()
                                        .watchType(WatchType.HIGH_SENSITIVITY)
                                        .issue(membership.getIssue())
                                        .sensitivityAtWatch(result.sensitivity().getScore())
                                        .expiresAt(expiresAt)
                                        .active(true)
                                        .build()));
            }
        });
    }

    @Transactional
    public void addFailureWarning(Long runId, Long articleId, String message) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        run.addWarning(CollectionRunWarning.builder()
                .code(CODE_ANALYSIS_FAILED)
                .message(CollectionRunWarning.truncateMessage("기사 " + articleId + " 분석 실패: " + message))
                .articleCount(1)
                .occurredAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
    }
}
