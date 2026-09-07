package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocalEventEvidenceTest {
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void joinsDifferentAnglesOfTheSameDatedSpeech() {
        var articles = List.of(
                article(1, "박가온, 교육 접근성 개선 필요", "박가온 미래시민당 대표는 12일 시민회관에서 교섭단체 대표연설을 했다. 교육 정책을 설명했다.", 0),
                article(2, "박가온, 주택 공급 방안 제시", "미래시민당 박가온 대표가 12일 시민회관 교섭단체 대표연설에서 주택 정책을 제시했다.", 1));
        assertTrue(evidence(articles));
        assertEquals(1, clusterer.cluster(articles).issues().size());
    }

    @Test
    void recurringSpeechOnAnotherDayIsNotAnEventContextMatch() {
        assertFalse(evidence(List.of(
                article(1, "박가온, 교육 접근성 개선 필요", "박가온 미래시민당 대표는 12일 시민회관에서 교섭단체 대표연설을 했다.", 0),
                article(2, "박가온, 주택 공급 방안 제시", "미래시민당 박가온 대표가 13일 시민회관 교섭단체 대표연설에서 발언했다.", 1))));
    }

    @Test
    void joinsAQuotedExcerptToAFullSpeechWithoutSharedTitleWords() {
        var articles = List.of(
                article(1, "[전문] 박가온, 시민들의 미래를 이야기하다", "박가온은 다음과 같이 말했다. \"지역의 교육 기회를 넓히고 학생들의 안전한 통학 환경을 보장하겠습니다\"", 0),
                article(2, "\"교육 기회를 넓히고 학생들의 안전한 통학 환경을 보장\"", null, 1));
        assertTrue(evidence(articles));
        assertEquals(1, clusterer.cluster(articles).issues().size());
    }

    @Test
    void shortGenericQuotationDoesNotCreateAnEdge() {
        assertFalse(evidence(List.of(
                article(1, "\"AI 시대 왔다\" 반도체 매수세 확대", null, 0),
                article(2, "연구자의 인공지능 능력 논쟁", "연구자는 \"AI 시대 왔다\"라는 주장을 반박했다.", 1))));
    }

    @Test
    void eventWordAndVenueWithoutReciprocalSubjectsAreInsufficient() {
        assertFalse(evidence(List.of(
                article(1, "새빛재단 장학제도 발표", "새빛재단은 시민회관 대강당에서 12일 설명회를 열었다. 교육 기회를 설명했다.", 0),
                article(2, "해솔협회 환경계획 공개", "해솔협회는 시민회관 대강당에서 12일 설명회를 열었다. 환경 활동을 설명했다.", 1))));
    }

    @Test
    void quotationEvidenceStillRespectsTheExistingTimeWindow() {
        var articles = List.of(
                article(1, "[전문] 박가온, 시민들의 미래를 이야기하다", "\"지역의 교육 기회를 넓히고 학생들의 안전한 통학 환경을 보장하겠습니다\"", 0),
                article(2, "\"교육 기회를 넓히고 학생들의 안전한 통학 환경을 보장\"", null, 25));
        assertTrue(evidence(articles));
        assertEquals(2, clusterer.cluster(articles).issues().size());
    }

    @Test
    void oneHeadlineEntityAndSharedBackgroundDoNotJoinDistinctStories() {
        var articles = List.of(
                article(1, "AGI 낙관론에 투자자 자금 유입…주식 계좌 순매수 증가", "AGI GPT6 HBM4 소식은 배경이다. 고객의 계좌 거래를 집계했다.", 0),
                article(2, "AGI 기준 논쟁…연구자들 독립 평가 촉구", "AGI GPT6 HBM4를 언급하며 개념의 정의를 논의했다.", 1));
        ClusterPlan plan = clusterer.cluster(articles, true);
        assertTrue(plan.pairScores().getFirst().entityOverlap() >= 2);
        assertFalse(plan.pairScores().getFirst().entityTitleSupported());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void matchingIndexMovementNeedsBothHeadlineSubjectsAndTheSameRate() {
        var first = article(1, "한빛지수 급등…매수세 유입", "한빛지수는 오전 3% 상승했다.", 0);
        var same = article(2, "외국인 복귀, 한빛지수 급등", "한빛지수가 3%대의 강세를 보였다.", 1);
        var different = article(2, "외국인 복귀, 한빛지수 급등", "한빛지수가 5%대의 강세를 보였다.", 1);
        assertTrue(evidence(List.of(first, same)));
        assertFalse(evidence(List.of(first, different)));
    }

    @Test
    void compoundPortfolioNicknameNeedsTwoCommonCompaniesAndAMovementInBothLeads() {
        var first = article(1, "가온·해솔, 동반 오름세", "삼성전자와 SK하이닉스가 오전장에서 상승했다.", 0);
        var same = article(2, "가온해솔 화색…수급 회복", "삼성전자와 SK하이닉스의 주가가 강세다. 오픈AI는 배경 소식이다.", 1);
        var different = article(2, "가온해솔 화색…수급 회복", "삼성전자와 마이크론의 주가가 강세다.", 1);
        var notMovement = article(2, "가온해솔 화색…수급 회복", "삼성전자와 SK하이닉스의 기술 전략을 비교했다.", 1);
        assertTrue(evidence(List.of(first, same)));
        assertFalse(evidence(List.of(first, different)));
        assertFalse(evidence(List.of(first, notMovement)));
    }

    @Test
    void markedTranscriptAllowsSpacingVariantsInLongQuotedHeadlines() {
        var articles = List.of(
                article(1, "[전문] 박가온의 미래 비전", "발언 전문입니다.\n\n교육 접근성을 높이고 학생 안전을 지키겠습니다.\n\n지역 사회의 교육 환경을 계속 개선하겠습니다.", 0),
                article(2, "\"교육접근성 높이고 학생안전 지키겠다…지역사회 교육환경 개선\"", null, 1));
        assertTrue(evidence(articles));
    }

    @Test
    void pastInterviewInBackgroundDoesNotIdentifyTheCurrentEvent() {
        assertFalse(evidence(List.of(
                article(1, "새빛연구원, 하늘학교 건립 협의",
                        "새빛연구원은 하늘학교 건립을 협의했다. 앞서 박가온 교장은 12일 시민회관 인터뷰에서 교육협력을 강조했다.", 0),
                article(2, "박가온, 새빛연구원 교육협력 강조",
                        "새빛연구원 교육협력에 관한 박가온 하늘학교 교장의 12일 시민회관 인터뷰가 공개됐다.", 1))));
    }

    @Test
    void secondaryQuotationInAggregatedSummaryDoesNotIdentifyThePrimaryHeadline() {
        var first = article(1, "\"지역의 교육 기회를 넓히고 학생들의 안전을 보장\"", null, 0);
        var second = new ClusterArticle(2, 1, "\"해외 운송 절차를 다시 점검하겠다\"",
                "협회는 \"해외 운송 절차를 다시 점검하겠다\"고 말했다. 지역 소식: \"지역의 교육 기회를 넓히고 학생들의 안전을 보장하겠다\"",
                null, FetchStatus.METADATA_ONLY, 2L, "fixture-2", new BigDecimal("0.8"),
                first.publishedAt(), first.observedAt(), List.of(), null, null, null, true);
        assertFalse(evidence(List.of(first, second)));
        assertEquals(2, clusterer.cluster(List.of(first, second)).issues().size());
    }

    private boolean evidence(List<ClusterArticle> articles) {
        return new FocalEventEvidence(articles, new BreakingNewsDetector()).matches(1, 2);
    }

    private ClusterArticle article(long id, String title, String body, int hour) {
        var time = OffsetDateTime.parse("2026-01-12T00:00:00+09:00").plusHours(hour);
        return new ClusterArticle(id, 1, title, null, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(),
                null, null, null, true);
    }
}
