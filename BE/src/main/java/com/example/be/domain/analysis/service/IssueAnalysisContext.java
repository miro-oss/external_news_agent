package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 대표 분석과 함께 보낼 이슈 멤버의 detached snapshot. */
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
                .sorted(Comparator.comparing(Article::getId))
                .toList();
        primaryTargetArticleIds = primaryTargetArticleIds == null
                ? Set.of()
                : Set.copyOf(primaryTargetArticleIds);
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
