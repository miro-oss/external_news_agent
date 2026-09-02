package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubArticleAnalyzerTest {

    private final StubArticleAnalyzer analyzer = new StubArticleAnalyzer(SensitivityCalculator.defaults());

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
        assertEquals(com.example.be.domain.analysis.entity.SensitivityLevel.HIGH,
                SensitivityCalculator.defaults().level(result.sensitivity().getScore()));
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

    @Test
    void excludesBoilerplateFromClassificationKeywords() {
        Article article = Article.builder()
                .title("반도체 장비 공급 계약 체결")
                .body("반도체 장비 공급 계약을 체결했다. 무단 전재 및 재배포 금지.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals(com.example.be.domain.analysis.entity.SensitivityLevel.HIGH,
                SensitivityCalculator.defaults().level(result.sensitivity().getScore()));
        assertEquals(3, result.sensitivity().dealSignal().score());
        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        assertEquals(1, result.keyPoints().size());
    }

    @Test
    void skipsEnglishPublisherBoilerplate() {
        Article article = Article.builder()
                .title("Chipmaker expands HBM production")
                .body("Sign up to continue reading. All rights reserved. "
                        + "The chipmaker expands HBM production this year.")
                .language("en")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals(1, result.keyPoints().size());
        assertEquals("The chipmaker expands HBM production this year.",
                result.keyPoints().get(0).text());
    }

    @Test
    void keepsSubstantiveMembershipSentence() {
        Article article = Article.builder()
                .title("플랫폼 회원 증가")
                .body("유료 회원 가입 증가가 반도체 교육 플랫폼의 매출 성장을 이끌었다.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals("유료 회원 가입 증가가 반도체 교육 플랫폼의 매출 성장을 이끌었다.",
                result.summary());
        assertEquals(1, result.keyPoints().size());
    }

    @Test
    void bindsEachSensitivityAxisToItsMatchingSection() {
        Article article = Article.builder()
                .title("투자 전망이 언급된 기사")
                .body("회사는 일반 현황을 설명했다. 경쟁사가 신제품을 출시했다. 정부가 수출 통제를 강화했다.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();

        AnalysisResult result = analyzer.analyze(article);

        assertEquals(null, result.sensitivity().customerMove().score());
        assertEquals(List.of(1), result.sensitivity().competitorThreat().evidenceSentenceIds());
        assertEquals(List.of(2), result.sensitivity().industryShift().evidenceSentenceIds());
        assertTrue(result.sections().get(1).text().contains("신제품"));
        assertTrue(result.sections().get(2).text().contains("수출 통제"));
    }
}
