package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 대표 분석과 함께 보낼 이슈 멤버의 detached snapshot. */
public record IssueAnalysisContext(
        Long issueId,
        Long representativeArticleId,
        List<Article> articles
) {

    public IssueAnalysisContext {
        articles = articles == null
                ? List.of()
                : articles.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Article::getId))
                .toList();
    }

    public static IssueAnalysisContext empty() {
        return new IssueAnalysisContext(null, null, List.of());
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
