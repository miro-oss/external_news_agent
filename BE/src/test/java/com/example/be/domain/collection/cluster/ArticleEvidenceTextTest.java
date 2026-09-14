package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleEvidenceTextTest {
    private static final String SUMMARY = "해솔연구원은 12일 저전력 센서 검증 결과를 발표했다.";

    @Test
    void absentOrFooterOnlyBodyAllowsTheSummary() {
        for (String body : new String[]{null, "  ", ClusterTestFixtures.PUBLISHER_FOOTER}) {
            ArticleEvidenceText.Selection selected = ArticleEvidenceText.select(body, SUMMARY);
            assertTrue(selected.fromSummary());
            assertEquals(SUMMARY, selected.text());
        }
    }

    @Test
    void captionOnlyBodyAllowsTheSummary() {
        for (String body : new String[]{
                "해솔연구원 실험실 전경. [사진=해솔연구원 제공]",
                "[사진=해솔연구원]\n사진 제공: 해솔연구원",
                "해솔연구원 실험실 모습. /사진=해솔연구원",
                "[자료사진]\n해솔연구원 실험실 외관."}) {
            ArticleEvidenceText.Selection selected = ArticleEvidenceText.select(body, SUMMARY);
            assertTrue(selected.fromSummary(), body);
            assertEquals(SUMMARY, selected.text());
        }
    }

    @Test
    void aShortArticleKeepsItsOwnEventInsteadOfTheSummariesOtherStory() {
        String body = "구름대학교는 장학제도를 개편했다.";
        ArticleEvidenceText.Selection selected = ArticleEvidenceText.select(body, SUMMARY);
        assertFalse(selected.fromSummary());
        assertEquals(body, selected.text());
    }

    @Test
    void unrecognizedFragmentsAreNotAssumedToBeCaptions() {
        String body = "공공 장비 검증을 위한 현장 지원";
        assertEquals(body, ArticleEvidenceText.primary(body, SUMMARY));
    }

    @Test
    void leadingCaptionsDoNotConsumeTheEvidenceBudgetOrReplaceTheRealArticle() {
        String captions = "구름대학교 연구동 전경. [사진=구름대학교]\n".repeat(35);
        String body = "구름대학교는 기초 실험 장비를 공개했다. 실습생은 다음 달부터 장비를 사용한다.";
        String selected = ArticleEvidenceText.primary(captions + body, SUMMARY);
        assertEquals(body, selected);
        assertEquals(body.substring(0, 20), ArticleEvidenceText.foreground(captions + body, 20));
    }

    @Test
    void anArticleFollowingACreditInTheSameExtractedLineIsPreserved() {
        String body = "연구동 전경. [사진=구름대학교 제공] " + SUMMARY;
        assertEquals(SUMMARY, ArticleEvidenceText.primary(body, "다른 사건"));
    }

    @Test
    void aReportingSentenceBeforeAPhotoCreditIsNotDiscarded() {
        String body = SUMMARY + " [사진=해솔연구원]";
        assertEquals(SUMMARY, ArticleEvidenceText.primary(body, "다른 사건"));
        String action = "구름대학교는 연구 장비를 지원한다.";
        assertEquals(action, ArticleEvidenceText.primary(action + " [사진=구름대학교]", "별도 소식"));
    }

    @Test
    void photoResearchProseAndLaterParagraphsRemainArticleEvidence() {
        String body = "해솔연구원은 사진 인식 기술을 개발했다고 밝혔다.\n"
                + "연구팀 사진\n학습 영상의 분석 비용을 줄이는 방법을 제시했다.";
        assertEquals(body, ArticleEvidenceText.primary(body, SUMMARY));
    }

    @Test
    void displayControlsAndFooterDoNotHideTheBody() {
        String body = "공유\n글자 크기\n" + SUMMARY + "\n" + ClusterTestFixtures.PUBLISHER_FOOTER;
        assertEquals(SUMMARY, ArticleEvidenceText.primary(body, "별도 소식"));
    }
}
