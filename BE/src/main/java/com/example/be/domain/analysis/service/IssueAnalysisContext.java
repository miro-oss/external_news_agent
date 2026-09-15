package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 실제 전문을 확보한 기사만 담는 분석용 이슈 snapshot. */
public record IssueAnalysisContext(
        Long issueId,
        Long representativeArticleId,
        List<Article> articles,
        Set<Long> primaryTargetArticleIds,
        BigDecimal importanceScore
) {

    public IssueAnalysisContext {
        articles = articles == null
                ? List.of()
                : articles.stream()
                .filter(Objects::nonNull)
                .filter(Article::hasFullText)
                .sorted(Comparator.comparing(Article::getId))
                .toList();
        Set<Long> availableArticleIds = articles.stream().map(Article::getId).collect(Collectors.toSet());
        primaryTargetArticleIds = primaryTargetArticleIds == null
                ? Set.of()
                : primaryTargetArticleIds.stream()
                .filter(availableArticleIds::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (!availableArticleIds.contains(representativeArticleId)) {
            representativeArticleId = null;
        }
    }

    public IssueAnalysisContext(Long issueId,
                                Long representativeArticleId,
                                List<Article> articles) {
        this(issueId, representativeArticleId, articles, Set.of(), null);
    }

    public IssueAnalysisContext(Long issueId,
                                Long representativeArticleId,
                                List<Article> articles,
                                Set<Long> primaryTargetArticleIds) {
        this(issueId, representativeArticleId, articles, primaryTargetArticleIds, null);
    }

    public static IssueAnalysisContext empty() {
        return new IssueAnalysisContext(null, null, List.of(), Set.of(), null);
    }

    public boolean present() {
        return issueId != null && representativeArticleId != null && !articles.isEmpty();
    }

    public List<Article> membersExcept(Long articleId) {
        if (!present()) {
            return List.of();
        }
        return articles.stream()
                .filter(article -> !article.getId().equals(articleId))
                .toList();
    }

    public Article article(Long articleId) {
        return articles.stream()
                .filter(article -> article.getId().equals(articleId))
                .findFirst()
                .orElse(null);
    }
}
