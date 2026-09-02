package com.example.be.domain.issues.service;

import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.entity.IssueStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
