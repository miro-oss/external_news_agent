package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.sources.entity.Source;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialCorrectionPolicyTest {

    private final OfficialCorrectionPolicy policy =
            new OfficialCorrectionPolicy(new IssueStanceClassifier());

    @Test
    void acceptsCorrectionOnlyFromRegisteredEntityFeedDomain() {
        NewsIssue issue = NewsIssue.builder().entities(List.of("삼성전자")).build();
        Source source = Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("삼성전자 뉴스룸")
                .urlTemplate("https://news.samsung.com/kr/feed")
                .build();
        Article correction = Article.builder()
                .id(1L)
                .title("삼성전자 HBM4 보도 정정")
                .summary("기존 보도가 오보라고 밝혔다.")
                .canonicalUrl("https://news.samsung.com/kr/hbm4-correction")
                .source(source)
                .build();

        assertTrue(policy.matches(issue, membership(issue, correction)));
    }

    @Test
    void rejectsUnregisteredPublisherEvenWithCorrectionWording() {
        NewsIssue issue = NewsIssue.builder().entities(List.of("삼성전자")).build();
        Source source = Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("일반 경제지")
                .urlTemplate("https://example.com/feed")
                .build();
        Article correction = Article.builder()
                .id(1L)
                .title("삼성전자 HBM4 보도 정정")
                .canonicalUrl("https://example.com/article/1")
                .source(source)
                .build();

        assertFalse(policy.matches(issue, membership(issue, correction)));
    }

    @Test
    void rejectsCorrectionHostedOutsideRegisteredEntityDomain() {
        NewsIssue issue = NewsIssue.builder().entities(List.of("삼성전자")).build();
        Source source = Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("삼성전자 뉴스룸")
                .urlTemplate("https://news.samsung.com/kr/feed")
                .build();
        Article correction = Article.builder()
                .id(2L)
                .title("삼성전자 HBM4 보도 정정")
                .canonicalUrl("https://news.samsung.com.evil.example/article/1")
                .source(source)
                .build();

        assertFalse(policy.matches(issue, membership(issue, correction)));
    }

    private IssueArticle membership(NewsIssue issue, Article article) {
        return IssueArticle.builder()
                .issue(issue)
                .article(article)
                .role(IssueArticleRole.MEMBER)
                .stance(IssueStance.RETRACTS)
                .stanceSource(IssueStanceSource.RULE)
                .stanceConfidence(new BigDecimal("0.850"))
                .build();
    }
}
