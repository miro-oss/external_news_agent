package com.example.be.domain.issues.service;

import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IssueImportanceCalculatorTest {

    private final IssueImportanceCalculator calculator =
            new IssueImportanceCalculator(mock(TopicFitScorer.class));
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-02T12:00:00+09:00");

    @Test
    void reachesOneHundredWhenAllFactorsAreSaturated() {
        assertEquals(new BigDecimal("100.00"), calculator.score(
                new BigDecimal("100"), 10, 3, 10, now, 1.0d, IssueStatus.EMERGING, now));
    }

    @Test
    void appliesStatusPenaltyAfterWeightedFactors() {
        assertEquals(new BigDecimal("80.00"), calculator.score(
                new BigDecimal("100"), 10, 3, 10, now, 1.0d, IssueStatus.DISPUTED, now));
        assertEquals(new BigDecimal("50.00"), calculator.score(
                new BigDecimal("100"), 10, 3, 10, now, 1.0d, IssueStatus.RETRACTED, now));
    }

    @Test
    void halvesRecencyContributionAfterTwentyFourHours() {
        assertEquals(new BigDecimal("7.50"), calculator.score(
                BigDecimal.ZERO,
                0,
                0,
                0,
                now.minusHours(24),
                0.0d,
                IssueStatus.EMERGING,
                now));
    }

    @Test
    void technologyTopicPromotesFocusedOriginalOverWidelyRepeatedPeripheralNews() {
        Topic topic = Topic.builder().queryText("AI 모델").optionalKeywords(List.of("LLM", "추론")).build();
        var actual = new IssueImportanceCalculator(new TopicFitScorer((language, keywords) -> Map.of()));
        NewsIssue release = issue(topic, 1, 1, 1, BigDecimal.ZERO, IssueStatus.EMERGING);
        NewsIssue widelyReported = issue(topic, 10, 3, 10, new BigDecimal("100"), IssueStatus.EMERGING);

        BigDecimal originalScore = actual.calculate(release, members(topic, "LLM 추론 개선"), now);
        BigDecimal repeatedScore = actual.calculate(widelyReported, members(topic, "LLM 기업 소식"), now);

        assertTrue(originalScore.compareTo(repeatedScore) > 0);
    }

    @Test
    void industryTopicKeepsItsExistingWeightsAndTechnologyKeepsRetractionPenalty() {
        var actual = new IssueImportanceCalculator(new TopicFitScorer((language, keywords) -> Map.of()));
        Topic industry = Topic.builder().queryText("AI 산업").optionalKeywords(List.of("LLM")).build();
        NewsIssue industrial = issue(industry, 10, 3, 10, BigDecimal.ZERO, IssueStatus.EMERGING);
        assertEquals(new BigDecimal("60.00"), actual.calculate(industrial, members(industry, "LLM"), now));

        Topic technology = Topic.builder().queryText("AI 모델").optionalKeywords(List.of("LLM")).build();
        NewsIssue retracted = issue(technology, 10, 3, 10, new BigDecimal("100"), IssueStatus.RETRACTED);
        assertEquals(new BigDecimal("50.00"), actual.calculate(retracted, members(technology, "LLM"), now));
    }

    @Test
    void unknownPublicationDatesNeverReceiveRecencyFromCollectionTime() {
        var actual = new IssueImportanceCalculator(new TopicFitScorer((language, keywords) -> Map.of()));
        Topic topic = Topic.builder().queryText("AI 모델").optionalKeywords(List.of("LLM")).build();
        NewsIssue issue = issue(topic, 1, 1, 1, BigDecimal.ZERO, IssueStatus.EMERGING);
        BigDecimal undated = actual.calculate(issue, members(topic, "LLM", null), now);
        BigDecimal publishedNow = actual.calculate(issue, members(topic, "LLM", now), now);

        assertEquals(new BigDecimal("35.00"), publishedNow.subtract(undated));
        // A new undated observation must not refresh an existing dated issue's recency either.
        var mixed = new java.util.ArrayList<>(members(topic, "LLM", now.minusHours(24)));
        mixed.addAll(members(topic, "LLM", null));
        assertEquals(new BigDecimal("17.50"), actual.calculate(issue, mixed, now).subtract(undated));
    }

    @Test
    void capturedTopicControlsBothFitAndWeightsWithoutChangingGlobalIssue() {
        var actual = new IssueImportanceCalculator(new TopicFitScorer((language, keywords) -> Map.of()));
        Topic captured = Topic.builder().queryText("AI 모델").optionalKeywords(List.of("LLM")).build();
        Topic current = Topic.builder().queryText("AI 산업").optionalKeywords(List.of("DRAM")).build();
        NewsIssue issue = issue(current, 10, 3, 10, BigDecimal.ZERO, IssueStatus.EMERGING);
        var memberships = members(current, "LLM");

        assertEquals(new BigDecimal("90.00"), actual.calculate(issue, memberships, now, captured));
        assertEquals(new BigDecimal("50.00"), actual.calculate(issue, memberships, now));
        assertEquals(current, issue.getTopic());
    }

    private NewsIssue issue(Topic topic, int articles, int publishers, int independent, BigDecimal sensitivity,
                            IssueStatus status) {
        return NewsIssue.builder().topic(topic).articleCount(articles).publisherCount(publishers)
                .independentContentCount(independent).sensitivityScore(sensitivity).lastSeenAt(now)
                .status(status).build();
    }

    private List<IssueArticle> members(Topic topic, String title) {
        return members(topic, title, now);
    }

    private List<IssueArticle> members(Topic topic, String title, OffsetDateTime publishedAt) {
        return List.of(IssueArticle.builder().article(Article.builder().topic(topic).title(title)
                .summary("").language("ko").publishedAt(publishedAt).build()).build());
    }
}
