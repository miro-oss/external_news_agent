package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.sources.entity.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;

/** 등록된 기업 FEED의 도메인과 이슈 엔티티가 모두 맞을 때만 공식 정정으로 인정한다. */
@Component
@RequiredArgsConstructor
class OfficialCorrectionPolicy {

    private final IssueStanceClassifier stanceClassifier;

    boolean matches(NewsIssue issue, IssueArticle membership) {
        if (membership.getStance() != IssueStance.RETRACTS) {
            return false;
        }
        Article article = membership.getArticle();
        Source source = article.getSource();
        if (source == null || !Source.KIND_FEED.equals(source.getSourceKind())
                || !stanceClassifier.hasExplicitCorrection(article)) {
            return false;
        }
        String sourceName = normalize(source.getName());
        boolean mappedEntity = issue.getEntities().stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .anyMatch(sourceName::contains);
        if (!mappedEntity) {
            return false;
        }
        String articleHost = host(article.getCanonicalUrl());
        String registeredHost = host(source.getUrlTemplate());
        return articleHost != null && registeredHost != null
                && (articleHost.equals(registeredHost)
                || articleHost.endsWith("." + registeredHost));
    }

    private String host(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return URI.create(value.trim()).getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .trim();
    }
}
