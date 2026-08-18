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
        if (findingRepository.existsByRunIdAndArticleId(runId, articleId)) {
            return;
        }

        CollectionRun run = runRepository.findById(runId).orElseThrow();
        Article article = articleRepository.findById(articleId).orElseThrow();
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
                .sections(result.sections())
                .analyzedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
    }

    @Transactional
    public void addFailureWarning(Long runId, Long articleId, String message) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        run.addWarning(CollectionRunWarning.builder()
                .code(CODE_ANALYSIS_FAILED)
                .message(trim("기사 " + articleId + " 분석 실패: " + message))
                .articleCount(1)
                .occurredAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
    }

    private String trim(String message) {
        return message.length() <= CollectionRunWarning.MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, CollectionRunWarning.MAX_MESSAGE_LENGTH);
    }
}
