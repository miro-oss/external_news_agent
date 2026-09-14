package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleEvidenceTextIntegrationTest {
    private static final String EDUCATION_QUOTE = "지역의 교육 기회를 넓히고 학생들의 안전한 통학 환경을 보장하겠습니다";

    @Test
    void lexicalLeadUsesShortBodyInsteadOfAnUnrelatedLongSummary() {
        String body = "구름연구원은 저전력센서 검증 절차를 공개했다. 현장 평가 결과를 설명했다.";
        var first = article(1, "구름연구원, 현장 평가 발표", body, "다른 행사에서 자동차 판매 결과를 발표했다.");
        var second = article(2, "저전력센서 검증 절차 공개", body, null);
        var evidence = new EventTextEvidence(List.of(first, second), new BreakingNewsDetector());
        assertEquals(1.0, evidence.compare(1, 2, Set.of()).leadSimilarity(), 1e-12);
    }

    @Test
    void lexicalLeadStartsAfterCaptionsAndCanUseASummaryForCaptionOnlyText() {
        String body = "구름연구원은 저전력센서 검증 절차를 공개했다. 현장 평가 결과를 설명했다.";
        String caption = "연구동 전경. [사진=구름연구원 제공]\n";
        var first = article(1, "구름연구원, 현장 평가 발표", caption.repeat(30) + body, "자동차 판매 결과");
        var second = article(2, "저전력센서 검증 절차 공개", caption, body);
        var evidence = new EventTextEvidence(List.of(first, second), new BreakingNewsDetector());
        assertEquals(1.0, evidence.compare(1, 2, Set.of()).leadSimilarity(), 1e-12);
    }

    @Test
    void aSubstantiveBodyDoesNotBorrowAQuotationFromAnUnrelatedSummary() {
        var first = article(1, "\"" + EDUCATION_QUOTE + "\"", null, null);
        var second = article(2, "구름협회, 운송 절차 개편",
                "구름협회는 해외 운송 절차를 개편했다. 선박 검사 절차가 달라진다.",
                "지역 소식: \"" + EDUCATION_QUOTE + "\"");
        assertFalse(focal(first, second));
    }

    @Test
    void realSpeechAfterLongCaptionsRemainsAvailableForQuotationEvidence() {
        var first = article(1, "\"" + EDUCATION_QUOTE + "\"", null, null);
        var second = article(2, "구름재단, 교육 계획 제시",
                "행사장 전경. [사진=구름재단]\n".repeat(120)
                        + "구름재단은 \"" + EDUCATION_QUOTE + "\"라고 밝혔다.",
                "운송 절차를 개편했다.");
        assertTrue(focal(first, second));
    }

    @Test
    void captionOnlyFallbackStillLimitsAnAggregatedSummaryToItsFirstQuotation() {
        var first = article(1, "\"" + EDUCATION_QUOTE + "\"", null, null);
        var unrelated = article(2, "구름협회, 운송 절차 개편", "행사장 전경. [사진=구름협회]",
                "협회는 \"해외 운송 절차를 다시 점검하겠다\"고 말했다. 다른 소식: \"" + EDUCATION_QUOTE + "\"");
        var same = article(2, "구름재단, 교육 계획 제시", "행사장 전경. [사진=구름재단]",
                "재단은 \"" + EDUCATION_QUOTE + "\"라고 밝혔다.");
        assertFalse(focal(first, unrelated));
        assertTrue(focal(first, same));
    }

    private static boolean focal(ClusterArticle first, ClusterArticle second) {
        return new FocalEventEvidence(List.of(first, second), new BreakingNewsDetector()).matches(1, 2);
    }

    private static ClusterArticle article(long id, String title, String body, String summary) {
        var time = OffsetDateTime.parse("2026-02-12T10:00:00+09:00");
        return new ClusterArticle(id, 1L, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }
}
