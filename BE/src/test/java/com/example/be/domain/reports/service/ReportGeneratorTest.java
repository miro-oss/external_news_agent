package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGeneratorTest {

    private final ReportGenerator generator = new ReportGenerator();

    @Test
    void buildsDeterministicMarkdownFromFindingsInPriorityOrder() {
        Finding low = finding(2L, "일반 기사", "일반 요약", RiskLevel.LOW, Relevance.WATCH, "기업");
        Finding high = finding(1L, "중요 기사", "중요 요약", RiskLevel.HIGH, Relevance.IMPORTANT, "정책");

        ReportDocument document = generator.generate(List.of(low, high),
                LocalDateTime.of(2026, 8, 18, 10, 30));

        assertEquals("반도체 뉴스 보고서 2026-08-18 10:30", document.title());
        assertEquals(ReportGenerator.MODEL_NAME, document.modelName());
        int highIndex = document.markdownBody().indexOf("중요 요약");
        int lowIndex = document.markdownBody().indexOf("일반 요약");
        assertTrue(highIndex >= 0 && lowIndex >= 0, "두 finding 요약이 모두 본문에 있어야 한다.");
        assertTrue(highIndex < lowIndex);
        assertTrue(document.markdownBody().contains("위험도: high 1 · medium 0 · low 1"));
        assertTrue(document.markdownBody().contains("원문: <https://example.com/1>"));
    }

    @Test
    void createsUsefulEmptyReportWhenRunHasNoFindings() {
        ReportDocument document = generator.generate(List.of(), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertEquals("뉴스 보고서 2026-08-18 09:00", document.title());
        assertTrue(document.markdownBody().contains("새로 분석된 기사가 없습니다"));
    }

    @Test
    void truncatesMultibyteTitleWithinOracleByteLimit() {
        Finding finding = finding(1L, "기사", "요약", RiskLevel.LOW, Relevance.REFERENCE,
                "기업", "가".repeat(200));

        ReportDocument document = generator.generate(List.of(finding),
                LocalDateTime.of(2026, 8, 18, 10, 30));

        assertTrue(document.title().getBytes(StandardCharsets.UTF_8).length <= NewsReport.MAX_TITLE_LENGTH);
    }

    private Finding finding(Long id,
                            String title,
                            String summary,
                            RiskLevel riskLevel,
                            Relevance relevance,
                            String category) {
        return finding(id, title, summary, riskLevel, relevance, category, "반도체");
    }

    private Finding finding(Long id,
                            String title,
                            String summary,
                            RiskLevel riskLevel,
                            Relevance relevance,
                            String category,
                            String topicName) {
        Topic topic = Topic.builder().id(3L).name(topicName).build();
        Article article = Article.builder()
                .id(id + 100)
                .topic(topic)
                .title(title)
                .canonicalUrl("https://example.com/" + id)
                .build();
        return Finding.builder()
                .id(id)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary(summary)
                .keyPoints(List.of(new FindingKeyPoint("핵심 포인트", List.of(0), "grounded")))
                .riskLevel(riskLevel)
                .relevance(relevance)
                .category(category)
                .build();
    }
}
