package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.ContentGroup;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.sources.entity.Source;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssueStatusCalculatorTest {

    private final OfficialCorrectionPolicy officialPolicy = mock(OfficialCorrectionPolicy.class);
    private final IssueStatusCalculator calculator = new IssueStatusCalculator(officialPolicy);

    @Test
    void prioritizesTwoIndependentLlmRetractions() {
        NewsIssue issue = issue(IssueCrossSource.empty());
        List<IssueArticle> memberships = List.of(
                membership(1L, issue, article(1L, "매체A", null), IssueStance.RETRACTS,
                        IssueStanceSource.LLM, "0.9"),
                membership(2L, issue, article(2L, "매체B", null), IssueStance.RETRACTS,
                        IssueStanceSource.LLM, "0.9"));

        assertEquals(IssueStatus.RETRACTED, calculator.calculate(issue, memberships).status());
    }

    @Test
    void syndicatedRetractionsDoNotCountAsIndependentConfirmation() {
        NewsIssue issue = issue(IssueCrossSource.empty());
        ContentGroup shared = ContentGroup.builder().id(90L).build();
        Article first = article(1L, "매체A", shared);
        Article second = article(2L, "매체B", shared);

        IssueStatusCalculator.Projection result = calculator.calculate(issue, List.of(
                membership(1L, issue, first, IssueStance.RETRACTS, IssueStanceSource.LLM, "0.9"),
                membership(2L, issue, second, IssueStance.RETRACTS, IssueStanceSource.LLM, "0.9")));

        assertEquals(IssueStatus.EMERGING, result.status());
    }

    @Test
    void independentCrossSourceConflictOutranksCorroboration() {
        NewsIssue issue = issue(new IssueCrossSource(
                List.of(),
                List.of(),
                List.of(new IssueCrossSource.Conflict(List.of(1L, 2L), "3조원과 5조원으로 갈림")),
                List.of()));
        List<IssueArticle> memberships = List.of(
                membership(1L, issue, article(1L, "매체A", null), IssueStance.SUPPORTS,
                        IssueStanceSource.RULE, "0.9"),
                membership(2L, issue, article(2L, "매체B", null), IssueStance.SUPPORTS,
                        IssueStanceSource.RULE, "0.9"));

        assertEquals(IssueStatus.DISPUTED, calculator.calculate(issue, memberships).status());
    }

    @Test
    void corroboratesOnlyHighConfidenceIndependentSupport() {
        NewsIssue issue = issue(IssueCrossSource.empty());
        List<IssueArticle> memberships = List.of(
                membership(1L, issue, article(1L, "매체A", null), IssueStance.SUPPORTS,
                        IssueStanceSource.RULE, "0.85"),
                membership(2L, issue, article(2L, "매체B", null), IssueStance.SUPPORTS,
                        IssueStanceSource.RULE, "0.80"));

        assertEquals(IssueStatus.CORROBORATED, calculator.calculate(issue, memberships).status());
    }

    @Test
    void officialCorrectionCanRetractWithoutTwoLlmConfirmations() {
        NewsIssue issue = issue(IssueCrossSource.empty());
        IssueArticle correction = membership(
                1L, issue, article(1L, "공식", null), IssueStance.RETRACTS,
                IssueStanceSource.RULE, "0.85");
        when(officialPolicy.matches(any(), any())).thenReturn(true);

        assertEquals(IssueStatus.RETRACTED,
                calculator.calculate(issue, List.of(correction)).status());
    }

    private NewsIssue issue(IssueCrossSource crossSource) {
        return NewsIssue.builder().id(10L).crossSource(crossSource).build();
    }

    private Article article(Long id, String publisher, ContentGroup contentGroup) {
        Source source = Source.builder().id(id).name(publisher).build();
        Article article = Article.builder()
                .id(id)
                .title("기사 " + id)
                .source(source)
                .sourceName(publisher)
                .build();
        article.assignContentGroup(contentGroup);
        return article;
    }

    private IssueArticle membership(Long id,
                                    NewsIssue issue,
                                    Article article,
                                    IssueStance stance,
                                    IssueStanceSource source,
                                    String confidence) {
        return IssueArticle.builder()
                .id(id)
                .issue(issue)
                .article(article)
                .role(IssueArticleRole.MEMBER)
                .stance(stance)
                .stanceSource(source)
                .stanceConfidence(new BigDecimal(confidence))
                .joinedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .build();
    }
}
