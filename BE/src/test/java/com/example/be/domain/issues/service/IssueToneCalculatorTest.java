package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingAnalysisBullet;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.ContentGroup;
import com.example.be.domain.issues.entity.IssueArticle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IssueToneCalculatorTest {

    private final IssueToneCalculator calculator = new IssueToneCalculator();

    @Test
    void countsEachOpinionArticleOnceAndKeepsNeutralInTheDenominator() {
        Article positive = article(1, null);
        Article neutral = article(2, null);
        Article negative = article(3, null);
        Article factOnly = article(4, null);
        Article unanalyzedMember = article(5, null);
        Finding repeatedOpinions = finding(1, positive, Sentiment.POSITIVE,
                opinion(), opinion(), point("FORECAST", "grounded", null, List.of(0)));

        var result = calculator.calculate(memberships(positive, neutral, negative, factOnly, unanalyzedMember),
                List.of(repeatedOpinions, finding(2, neutral, Sentiment.NEUTRAL, opinion()),
                        finding(3, negative, Sentiment.NEGATIVE, opinion()),
                        finding(4, factOnly, Sentiment.POSITIVE, point("FACT", "grounded", null, List.of(0)))));

        assertEquals(4, result.analyzedArticleCount());
        assertEquals(3, result.sampleCount());
        assertEquals(1, result.optimisticCount());
        assertEquals(1, result.neutralCount());
        assertEquals(1, result.pessimisticCount());
        assertEquals(new BigDecimal("33.33"), result.optimisticPercent());
        assertEquals(new BigDecimal("33.33"), result.neutralPercent());
        assertEquals(new BigDecimal("33.33"), result.pessimisticPercent());
    }

    @Test
    void usesLatestAnalysisAndDeduplicatesReprintsWithoutCollidingWithArticleIds() {
        Article original = article(1, 3L);
        Article reprint = article(2, 3L);
        Article standalone = article(3, null);
        Finding reused = Finding.builder().id(30L).article(reprint).analysisSource(AnalysisSource.REUSED)
                .sentiment(Sentiment.NEGATIVE).keyPoints(List.of(opinion())).build();

        var result = calculator.calculate(memberships(original, reprint, standalone), List.of(
                reused, finding(10, original, Sentiment.NEGATIVE, opinion()),
                finding(21, standalone, Sentiment.POSITIVE, opinion()),
                finding(20, original, Sentiment.POSITIVE, opinion())));

        assertEquals(3, result.analyzedArticleCount());
        assertEquals(2, result.sampleCount());
        assertEquals(1, result.optimisticCount());
        assertEquals(1, result.pessimisticCount());
        assertEquals(new BigDecimal("50.00"), result.optimisticPercent());
        assertEquals(new BigDecimal("0.00"), result.neutralPercent());
    }

    @Test
    void latestStubOrMissingOpinionDoesNotReviveOlderOpinionsEvenAcrossReprints() {
        Article original = article(1, 5L);
        Article reprint = article(2, 5L);
        Article removedOpinion = article(3, null);
        Finding stub = Finding.builder().id(20L).article(reprint).analysisSource(AnalysisSource.STUB)
                .sentiment(Sentiment.POSITIVE).keyPoints(List.of(opinion())).build();

        var result = calculator.calculate(memberships(original, reprint, removedOpinion), List.of(
                finding(10, original, Sentiment.POSITIVE, opinion()),
                finding(11, reprint, Sentiment.POSITIVE, opinion()), stub,
                finding(12, removedOpinion, Sentiment.POSITIVE, opinion()),
                finding(21, removedOpinion, Sentiment.NEUTRAL, point("FACT", "grounded", null, List.of(0)))));

        assertEquals(2, result.analyzedArticleCount());
        assertEquals(0, result.sampleCount());
        assertNull(result.optimisticPercent());
        assertNull(result.neutralPercent());
        assertNull(result.pessimisticPercent());
    }

    @Test
    void excludesWeakUnattributedAndUnsupportedOpinionsAndUnrelatedFindings() {
        Article article = article(1, null);
        var result = calculator.calculate(memberships(article), List.of(
                finding(1, article, Sentiment.POSITIVE,
                        point("OPINION", "weak", "기자", List.of(0)),
                        point("OPINION", "ungrounded", "기자", List.of(0)),
                        point("OPINION", "grounded", " ", List.of(0)),
                        point("OPINION", "grounded", "기자", List.of()),
                        point("OPINION", "grounded", "기자", List.of(-1)),
                        point("FORECAST", "grounded", "기자", List.of(0))),
                finding(2, article(2, null), Sentiment.NEGATIVE, opinion())));

        assertEquals(1, result.analyzedArticleCount());
        assertEquals(0, result.sampleCount());
        assertEquals(0, result.optimisticCount());
        assertEquals(0, result.neutralCount());
        assertEquals(0, result.pessimisticCount());
    }

    @Test
    void readsEffectiveStructuredSectionsAndCountsZeroBasedEvidence() {
        Article article = article(1, null);
        Finding structured = Finding.builder().id(1L).article(article).analysisSource(AnalysisSource.LLM)
                .sentiment(Sentiment.NEUTRAL).keyPoints(List.of())
                .analysisSections(List.of(new FindingAnalysisSection("견해", List.of(
                        new FindingAnalysisBullet("애널리스트는 영향을 중립적으로 평가했다.", List.of(0),
                                "grounded", BigDecimal.ONE, null, "OPINION", "애널리스트")))))
                .build();

        var result = calculator.calculate(memberships(article), List.of(structured));

        assertEquals(1, result.sampleCount());
        assertEquals(new BigDecimal("100.00"), result.neutralPercent());
        assertEquals(new BigDecimal("0.00"), result.optimisticPercent());
    }

    @Test
    void emptyIssueHasNoPercentageInsteadOfPretendingToBeNeutral() {
        var result = calculator.calculate(List.of(), List.of());
        assertEquals(0, result.analyzedArticleCount());
        assertEquals(0, result.sampleCount());
        assertNull(result.neutralPercent());
    }

    private Article article(long id, Long contentGroupId) {
        return Article.builder().id(id)
                .contentGroup(contentGroupId == null ? null : ContentGroup.builder().id(contentGroupId).build())
                .build();
    }

    private List<IssueArticle> memberships(Article... articles) {
        return List.of(articles).stream().map(article -> IssueArticle.builder().article(article).build()).toList();
    }

    private Finding finding(long id, Article article, Sentiment sentiment, FindingKeyPoint... points) {
        return Finding.builder().id(id).article(article).sentiment(sentiment)
                .analysisSource(AnalysisSource.LLM).keyPoints(List.of(points)).build();
    }

    private FindingKeyPoint opinion() {
        return point("OPINION", "grounded", "애널리스트", List.of(0));
    }

    private FindingKeyPoint point(String type, String groundedness, String attributedTo, List<Integer> evidence) {
        return new FindingKeyPoint("애널리스트의 평가", evidence, groundedness, null, type, attributedTo);
    }
}
