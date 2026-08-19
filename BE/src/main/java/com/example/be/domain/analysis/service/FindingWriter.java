package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FindingWriter {

    public static final String CODE_ANALYSIS_FAILED = "ANALYSIS_FAILED";

    private final FindingRepository findingRepository;
    private final CollectionRunRepository runRepository;
    private final ArticleRepository articleRepository;

    @Transactional
    public void write(Long runId, Long articleId, ChangeType changeType, AnalysisResult result) {
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
                .keyPoints(result.keyPoints())
                .intent(result.intent())
                .sentiment(result.sentiment())
                .riskLevel(result.riskLevel())
                .relevance(result.relevance())
                .category(result.category())
                .analysisSource(result.analysisSource())
                .sections(result.sections())
                .analysisSections(result.analysisSections())
                .entities(result.entities())
                .promptVersion(result.metadata().promptVersion())
                .llmProvider(result.metadata().provider())
                .llmModel(result.metadata().model())
                .inputTokens(result.metadata().inputTokens())
                .outputTokens(result.metadata().outputTokens())
                .costUsd(result.metadata().costUsd())
                .credits(result.metadata().credits())
                .inputTruncated(result.metadata().truncated())
                .analyzedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
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
