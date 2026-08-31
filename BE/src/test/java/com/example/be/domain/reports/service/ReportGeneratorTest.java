package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(document.markdownBody().contains("민감도: 높음 1 · 보통 0 · 낮음 1"));
        assertTrue(document.markdownBody().contains("원문: <https://example.com/1>"));
        assertEquals(List.of(1L, 2L), document.reflectedFindingIds());
        assertEquals(List.of(), document.excludedFindingIds());
    }

    /**
     * 본문은 보고서를 읽는 사람이 그대로 읽는 글이다. enum의 apiValue(high, important)나 finding 같은
     * 구현 용어가 새어 나가면 화면에서 고칠 방법이 없으므로 여기서 막는다.
     */
    @Test
    void writesReaderFacingKoreanLabelsInsteadOfApiValues() {
        Finding finding = finding(1L, "중요 기사", "중요 요약", RiskLevel.HIGH, Relevance.IMPORTANT, "정책");

        String body = generator.generate(List.of(finding), LocalDateTime.of(2026, 8, 18, 10, 30))
                .markdownBody();

        assertTrue(body.contains("- 전체 근거: 1건"));
        assertTrue(body.contains("- 분류: 정책 · 민감도: 높음 · 관련도: 중요"));
        assertFalse(body.contains("finding"));
        assertFalse(body.contains("high"));
        assertFalse(body.contains("important"));
    }

    @Test
    void createsUsefulEmptyReportWhenRunHasNoFindings() {
        ReportDocument document = generator.generate(List.of(), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertEquals("뉴스 보고서 2026-08-18 09:00", document.title());
        assertTrue(document.markdownBody().contains("분석 결과가 없어"));
    }

    @Test
    void excludesStubFindingsAndLeavesSourceNote() {
        Finding stub = Finding.builder()
                .id(1L)
                .article(Article.builder()
                        .id(101L)
                        .topic(Topic.builder().name("반도체").build())
                        .title("STUB 기사")
                        .canonicalUrl("https://example.com/stub")
                        .build())
                .changeType(ChangeType.NEW)
                .summary("본문에 들어가면 안 되는 STUB 요약")
                .keyPoints(List.of())
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("정책")
                .analysisSource(AnalysisSource.STUB)
                .build();

        ReportDocument document = generator.generate(
                List.of(stub),
                LocalDateTime.of(2026, 8, 18, 9, 0),
                new ReportSourceStats(1, 0, 0, 0, 1, 0));

        assertTrue(!document.markdownBody().contains("본문에 들어가면 안 되는 STUB 요약"));
        assertTrue(document.markdownBody().contains("임시 응답 분석 1건"));
    }

    @Test
    void includesReusedLlmFindingInFallbackContent() {
        Finding reused = Finding.builder()
                .id(2L)
                .article(Article.builder()
                        .id(102L)
                        .topic(Topic.builder().name("반도체").build())
                        .title("REUSED 기사")
                        .canonicalUrl("https://example.com/reused")
                        .build())
                .changeType(ChangeType.UPDATED)
                .summary("재사용된 REUSED 요약")
                .keyPoints(List.of(new FindingKeyPoint("재사용된 근거 주장", List.of(0), "grounded")))
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("기업")
                .analysisSource(AnalysisSource.REUSED)
                .build();

        ReportDocument document = generator.generate(
                List.of(reused),
                LocalDateTime.of(2026, 8, 18, 9, 0),
                new ReportSourceStats(1, 0, 0, 0, 0, 0));

        assertTrue(document.markdownBody().contains("재사용된 REUSED 요약"));
        assertTrue(document.markdownBody().contains("재사용된 근거 주장"));
    }

    @Test
    void treatsMissingAnalysisSourceAsUntrustedAtPublicBoundary() {
        Finding missingSource = Finding.builder()
                .id(3L)
                .article(Article.builder()
                        .id(103L)
                        .topic(Topic.builder().name("반도체").build())
                        .title("출처 없는 기사")
                        .canonicalUrl("https://example.com/missing-source")
                        .build())
                .changeType(ChangeType.NEW)
                .summary("포함되면 안 되는 요약")
                .keyPoints(List.of(new FindingKeyPoint("주장", List.of(0), "grounded")))
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("기업")
                .analysisSource(null)
                .build();

        ReportDocument document = generator.generate(
                List.of(missingSource), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertTrue(!document.markdownBody().contains("포함되면 안 되는 요약"));
    }

    @Test
    void excludesUngroundedFindingsAndKeyPointsFromFallbackContent() {
        Finding unsupported = finding(
                1L,
                "왜곡 기사",
                "보고서에 들어가면 안 되는 요약",
                RiskLevel.HIGH,
                Relevance.IMPORTANT,
                "기업",
                List.of(new FindingKeyPoint("근거 없는 주장", List.of(0), "ungrounded")));
        Finding mixed = finding(
                2L,
                "검증 기사",
                "검증된 기사 요약",
                RiskLevel.MEDIUM,
                Relevance.WATCH,
                "기업",
                List.of(
                        new FindingKeyPoint("검증된 주장", List.of(0), "grounded"),
                        new FindingKeyPoint("제외할 주장", List.of(1), "ungrounded")));

        ReportDocument document = generator.generate(
                List.of(unsupported, mixed), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertTrue(!document.markdownBody().contains("보고서에 들어가면 안 되는 요약"));
        assertTrue(!document.markdownBody().contains("근거 없는 주장"));
        assertTrue(!document.markdownBody().contains("제외할 주장"));
        assertTrue(document.markdownBody().contains("검증된 주장"));
        assertTrue(document.markdownBody().contains("근거 부족 분석 1건 제외"));
        assertTrue(document.markdownBody().contains("왜곡 기사 — 검증된 문장 근거가 없어 제외했습니다."));
        assertEquals(List.of(2L), document.reflectedFindingIds());
        assertEquals(List.of(1L), document.excludedFindingIds());
    }

    @Test
    void explainsWhenEvidenceFilteringRemovesEveryLlmFinding() {
        Finding unsupported = finding(
                1L,
                "왜곡 기사",
                "보고서에 들어가면 안 되는 요약",
                RiskLevel.HIGH,
                Relevance.IMPORTANT,
                "기업",
                List.of(new FindingKeyPoint("근거 없는 주장", List.of(0), "ungrounded")));

        ReportDocument document = generator.generate(
                List.of(unsupported), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertTrue(document.markdownBody().contains("근거가 확인된 분석 결과가 없어"));
        assertTrue(document.markdownBody().contains("근거 부족 분석 1건 제외"));
    }

    @Test
    void escapesMarkdownMetacharactersWithoutChangingAmpersands() {
        Finding finding = finding(
                1L, "TSMC & *삼성* [HBM]", "요약 _강조_", RiskLevel.HIGH, Relevance.IMPORTANT, "기업");

        ReportDocument document = generator.generate(
                List.of(finding), LocalDateTime.of(2026, 8, 18, 9, 0));

        assertTrue(document.markdownBody().contains("TSMC & \\*삼성\\* \\[HBM\\]"));
        assertTrue(document.markdownBody().contains("요약 \\_강조\\_"));
        assertTrue(!document.markdownBody().contains("&amp;"));
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
        return finding(id, title, summary, riskLevel, relevance, category,
                List.of(new FindingKeyPoint("핵심 포인트", List.of(0), "grounded")), topicName);
    }

    private Finding finding(Long id,
                            String title,
                            String summary,
                            RiskLevel riskLevel,
                            Relevance relevance,
                            String category,
                            List<FindingKeyPoint> keyPoints) {
        return finding(id, title, summary, riskLevel, relevance, category, keyPoints, "반도체");
    }

    private Finding finding(Long id,
                            String title,
                            String summary,
                            RiskLevel riskLevel,
                            Relevance relevance,
                            String category,
                            List<FindingKeyPoint> keyPoints,
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
                .keyPoints(keyPoints)
                .riskLevel(riskLevel)
                .relevance(relevance)
                .category(category)
                .analysisSource(AnalysisSource.LLM)
                .build();
    }
}
