package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightHypothesisEntityExtractorTest {

    private final InsightHypothesisEntityExtractor extractor =
            new InsightHypothesisEntityExtractor();

    @Test
    void extractsOnlyTrackingFieldEntitiesWithIssueVocabularyFallback() {
        NewsIssue issue = NewsIssue.builder()
                .topic(Topic.builder()
                        .requiredKeywords(List.of("HBM4"))
                        .optionalKeywords(List.of())
                        .build())
                .entities(List.of("삼성전자", "신생팹"))
                .build();
        AgentInsightResponse.Insight insight = new AgentInsightResponse.Insight(
                "CHIP_MAKER",
                "헤드라인의 ASML은 추적 대상이 아님",
                List.of(),
                List.of(new AgentInsightResponse.Implication(
                        "IMPLICATION",
                        "i1",
                        "본문의 TSMC도 추적 대상이 아님",
                        List.of(),
                        "삼성전자가 HBM4 일정을 유지한다",
                        "신생팹 공급이 중단된다")),
                List.of("HBM4 후속 발표"),
                BigDecimal.ONE);

        List<String> entities = extractor.extract(insight, issue);

        assertTrue(entities.containsAll(List.of("삼성전자", "HBM4", "신생팹")));
        assertFalse(entities.contains("ASML"));
        assertFalse(entities.contains("TSMC"));
    }
}
