package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** 같은 분석 입력으로 생성된 실제 LLM finding을 찾아 호출 없이 재사용한다. */
@Component
@RequiredArgsConstructor
public class FindingReuseCache {

    private final FindingRepository findingRepository;

    @Transactional(readOnly = true)
    public Lookup lookup(Article article) {
        String inputHash = ArticleHasher.analysisInputHash(
                article.getTitle(), article.getSummary(), article.getBody());
        Optional<Finding> source = findingRepository
                .findFirstByArticleIdAndAnalysisSourceAndAnalysisInputHashOrderByIdDesc(
                        article.getId(), AnalysisSource.LLM, inputHash);
        Optional<CachedAnalysis> cached = source.map(this::toCachedAnalysis);
        Optional<ChangeType> previousChangeType = source
                .map(Finding::getChangeType)
                .or(() -> findingRepository.findFirstByArticleIdOrderByIdDesc(article.getId())
                        .map(Finding::getChangeType));
        return new Lookup(inputHash, cached, previousChangeType);
    }

    private CachedAnalysis toCachedAnalysis(Finding finding) {
        AnalysisMetadata metadata = new AnalysisMetadata(
                finding.getPromptVersion(),
                finding.getLlmProvider(),
                finding.getLlmModel(),
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                finding.isInputTruncated());
        AnalysisResult result = new AnalysisResult(
                finding.getSummary(),
                finding.getEffectiveKeyPoints(),
                finding.getIntent(),
                finding.getSentiment(),
                finding.getRiskLevel(),
                finding.getRelevance(),
                finding.getCategory(),
                finding.getSections(),
                AnalysisSource.REUSED,
                finding.getAnalysisSections(),
                finding.getEntities(),
                metadata);
        return new CachedAnalysis(finding.getChangeType(), result);
    }

    public record Lookup(
            String analysisInputHash,
            Optional<CachedAnalysis> cached,
            Optional<ChangeType> previousChangeType
    ) {

        public Lookup {
            Objects.requireNonNull(analysisInputHash, "analysisInputHash는 필수입니다.");
            cached = cached == null ? Optional.empty() : cached;
            previousChangeType = previousChangeType == null ? Optional.empty() : previousChangeType;
        }
    }

    public record CachedAnalysis(ChangeType changeType, AnalysisResult result) {

        public CachedAnalysis {
            Objects.requireNonNull(changeType, "changeType은 필수입니다.");
            Objects.requireNonNull(result, "result는 필수입니다.");
        }
    }
}
