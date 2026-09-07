package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererEventEvidenceTest {
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void backgroundTechnicalTermsCannotJoinDifferentAnnouncements() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "연구팀, 추론 연산 효율 개선 결과 발표", "배경 기술로 HBM4 DDR5를 언급했다.", 0),
                article(2, "AMD, 개발자용 신규 워크스테이션 공개", "제품에는 HBM4 DDR5가 쓰인다.", 1)), true);
        assertEquals(2, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().entityOverlap() >= 2);
        assertFalse(plan.pairScores().getFirst().entityTitleSupported());
    }

    @Test
    void organizationNameAloneDoesNotJoinFactoryPlanAndTalentTheft() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "TSMC 일본 신규 공장 지원 검토", null, 0),
                article(2, "TSMC 인력 유출 수사 확대", null, 1)), true);
        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().organizationTitleSupported());
    }

    @Test
    void connectsKoreanSpacingAndCompoundVariantsWithTheSameAnnouncement() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "새빛연구원, 미래산업박람회서 스마트공장 기술교육 솔루션 선보여",
                        "새빛연구원은 미래산업박람회에서 스마트공장 기술교육 솔루션을 공개했다.", 0),
                article(2, "새빛연구원 '미래산업박람회' 스마트 공장 교육 솔루션 공개",
                        "미래산업박람회에 참가한 새빛연구원이 스마트 공장 교육 솔루션을 소개했다.", 1)), true);
        assertEquals(1, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().eventTextMatch());
    }

    @Test
    void lexicalEvidenceRespectsTheTimeWindowAndBreakingNewsWindow() {
        for (String prefix : List.of("", "[속보] ")) {
            ClusterPlan plan = clusterer.cluster(List.of(
                    article(1, prefix + "새빛연구원, 미래산업박람회서 스마트공장 기술교육 솔루션 선보여",
                            "새빛연구원은 미래산업박람회에서 스마트공장 기술교육 솔루션을 공개했다.", 0),
                    article(2, "새빛연구원 '미래산업박람회' 스마트 공장 교육 솔루션 공개",
                            "미래산업박람회에 참가한 새빛연구원이 스마트 공장 교육 솔루션을 소개했다.",
                            prefix.isEmpty() ? 25 : 7)), true);
            assertEquals(2, plan.issues().size());
        }
    }

    @Test
    void lexicalEvidenceStillCannotBridgeConflictingNamedVendors() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "한화세미텍, 미래산업박람회서 스마트공장 검사 솔루션 선보여",
                        "스마트공장 검사 솔루션을 미래산업박람회에서 공개했다.", 0),
                article(2, "디엠에스, 미래산업박람회 스마트 공장 검사 솔루션 공개",
                        "미래산업박람회에서 스마트 공장 검사 솔루션을 소개했다.", 1)));
        assertEquals(2, plan.issues().size());
    }

    @Test
    void sharedProductionWordAndPressBoilerplateCannotCreateAnEventEdge() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "가온모터스 부산 전기차 시험 생산 공식 발표",
                        "가온모터스 부산 전기차 시험 생산 공식 발표. 공시 자료 경영진 발표 투자 계획 세부 내용.", 0),
                article(2, "누리케미칼 울산 배터리 생산 공식 발표",
                        "누리케미칼 울산 배터리 생산 공식 발표. 공시 자료 경영진 발표 투자 계획 세부 내용.", 1)), true);
        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().eventTextMatch());
    }

    @Test
    void eventEvidenceIsStableWhenInputOrderChanges() {
        List<ClusterArticle> articles = List.of(
                article(1, "새빛연구원, 미래산업박람회서 스마트공장 기술교육 솔루션 선보여",
                        "새빛연구원은 미래산업박람회에서 스마트공장 기술교육 솔루션을 공개했다.", 0),
                article(2, "새빛연구원 '미래산업박람회' 스마트 공장 교육 솔루션 공개",
                        "미래산업박람회에 참가한 새빛연구원이 스마트 공장 교육 솔루션을 소개했다.", 1));
        assertEquals(clusterer.cluster(articles, true).pairScores(),
                clusterer.cluster(articles.reversed(), true).pairScores());
    }

    private ClusterArticle article(long id, String title, String summary, int hours) {
        OffsetDateTime time = OffsetDateTime.parse("2026-09-07T10:00:00+09:00").plusHours(hours);
        return new ClusterArticle(id, 7L, title, summary, null, FetchStatus.METADATA_ONLY,
                id, "매체" + id, new BigDecimal("0.8"), time, time, List.of("반도체"),
                null, null, null, true);
    }
}
