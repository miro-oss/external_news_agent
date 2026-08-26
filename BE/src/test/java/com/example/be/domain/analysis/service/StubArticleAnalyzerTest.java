package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubArticleAnalyzerTest {

    private final StubArticleAnalyzer analyzer = new StubArticleAnalyzer();

    @Test
    void splitsFullTextAndKeepsEvidenceIndexesStable() {
        Article article = Article.builder()
                .title("SK하이닉스, HBM4 양산 일정 앞당겨")
                .body("SK하이닉스가 HBM4 양산 일정을 앞당겼다. AI 서버 수요 증가가 배경이다.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals(2, result.sections().size());
        assertEquals(0, result.sections().get(0).index());
        assertEquals(result.sections().get(0).text(), result.keyPoints().get(0).text());
        assertEquals(0, result.keyPoints().get(0).evidence().get(0));
        assertEquals("grounded", result.keyPoints().get(0).groundedness());
        assertEquals(Relevance.IMPORTANT, result.relevance());
        assertEquals(Sentiment.POSITIVE, result.sentiment());
        assertEquals(AnalysisSource.STUB, result.analysisSource());
    }

    @Test
    void createsKoreanSummaryForEnglishExportControlArticle() {
        Article article = Article.builder()
                .title("US tightens export controls on advanced chipmaking tools")
                .summary("The government announced new restrictions for semiconductor equipment exports.")
                .language("en")
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.", result.summary());
        assertEquals("정책", result.category());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertFalse(result.sections().isEmpty());
        assertTrue(result.summary().matches(".*[가-힣].*"));
        assertEquals("weak", result.keyPoints().get(0).groundedness());
    }

    @Test
    void classifiesUnrelatedArticleAsReference() {
        Article article = Article.builder()
                .title("지역 축제 일정이 공개됐다")
                .summary("주말 행사와 공연 시간이 안내됐다.")
                .language("ko")
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .build();

        assertEquals(Relevance.REFERENCE, analyzer.analyze(article).relevance());
    }

    @Test
    void skipsPunctuationAndPublisherBoilerplateWhenChoosingSummaryAndKeyPoints() {
        Article article = Article.builder()
                .title("북방화창, HBM·칩렛 장비로 사업 확대")
                .body("! AI가 자동 생성한 요약으로 정확하지 않을 수 있어요. "
                        + "이 기사는 회원 가입이 필요한 프리미엄 기사입니다. "
                        + "북방화창이 반도체 장비 반기보고서를 발표했다. "
                        + "식각과 박막 증착 장비의 시장점유율이 상승했다.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals("북방화창이 반도체 장비 반기보고서를 발표했다.", result.summary());
        assertEquals("북방화창이 반도체 장비 반기보고서를 발표했다.",
                result.keyPoints().get(0).text());
        assertTrue(result.keyPoints().get(0).evidence().get(0) > 0);
        assertTrue(result.sections().stream().anyMatch(section -> section.text().equals("!")));
    }

    @Test
    void fallsBackToArticleTitleWhenEveryBodySentenceIsBoilerplate() {
        Article article = Article.builder()
                .title("반도체 장비 공급 계약 체결")
                .body("! AI가 자동 생성한 요약으로 정확하지 않을 수 있어요.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals("반도체 장비 공급 계약 체결", result.summary());
        assertTrue(result.keyPoints().isEmpty());
    }
}
