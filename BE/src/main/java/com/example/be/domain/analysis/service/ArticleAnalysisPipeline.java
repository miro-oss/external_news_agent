package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 외부 모델 호출 자리가 생겨도 DB 트랜잭션을 잡지 않도록 분석과 저장을 분리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleAnalysisPipeline {

    private final CollectionRunArticleRepository runArticleRepository;
    private final ArticleAnalyzer analyzer;
    private final FindingWriter findingWriter;

    public void analyze(Long runId) {
        for (Target target : targets(runId)) {
            try {
                AnalysisResult result = analyzer.analyze(target.article());
                findingWriter.write(runId, target.article().getId(), target.changeType(), result);
            } catch (RuntimeException exception) {
                log.warn("기사 분석에 실패했다. runId={} articleId={} error={}",
                        runId, target.article().getId(), exception.getMessage(), exception);
                findingWriter.addFailureWarning(runId, target.article().getId(), messageOf(exception));
            }
        }
    }

    private List<Target> targets(Long runId) {
        Map<Long, Target> byArticleId = new LinkedHashMap<>();
        for (CollectionRunArticle observation : runArticleRepository.findAnalysisTargetsByRunId(runId)) {
            Target candidate = new Target(observation.getArticle(), observation.getChangeType());
            byArticleId.merge(observation.getArticle().getId(), candidate, this::preferUpdated);
        }
        return List.copyOf(byArticleId.values());
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
