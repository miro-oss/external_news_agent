package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionCapacityEventEvidenceTest {
    @Test
    void matchesTwoProcessTargetsAcrossRelativeYearsSpacingAndVolumeUnits() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var second = article(2, "한빛반도체, 첨단 생산능력 확대",
                "한빛반도체는 공정 생산능력 확대 계획을 공유했다. "
                        + "4nm 공정의 월 생산량은 2028년 중반 70,000장으로 늘어날 전망이다. "
                        + "6㎚ 월 생산능력은 2028년 중반 90천장으로 확대할 계획이다.");
        assertTrue(evidence(first, second).matches(1, 2));
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void sameIntermediateCapacityCannotHideAChangedFinalTarget() {
        var first = ramp(1, "10만");
        var changed = ramp(2, "12만");
        assertFalse(evidence(first, changed).matches(1, 2));
        assertTrue(evidence(first, changed).conflicts(1, 2));
    }

    @Test
    void explicitDifferentMakerYearOrSharedProcessVolumeConflicts() {
        var first = plan(1, "한빛반도체", "2028년 중반", "4나노", "7만", "6나노", "9만");
        for (var other : List.of(plan(2, "푸른반도체", "2028년 중반", "4나노", "7만", "6나노", "9만"),
                plan(2, "한빛반도체", "2029년 중반", "4나노", "7만", "6나노", "9만"),
                plan(2, "한빛반도체", "2028년 중반", "4나노", "8만", "6나노", "9만"))) {
            assertFalse(evidence(first, other).matches(1, 2));
            assertTrue(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void additionalOrMissingProcessesAreUnknownUnlessTwoSharedTargetsAgree() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var extra = article(2, first.title(), first.body() + " 8나노 월 생산량은 내년 중반 12만장으로 확대할 계획이다.");
        var oneShared = plan(2, "한빛반도체", "내년 중반", "4나노", "7만", "8나노", "12만");
        var noneShared = plan(2, "한빛반도체", "내년 중반", "5나노", "7만", "7나노", "9만");
        assertTrue(evidence(first, extra).matches(1, 2));
        assertFalse(evidence(first, extra).conflicts(1, 2));
        assertTrue(evidence(extra, first).matches(2, 1));
        for (var other : List.of(oneShared, noneShared)) {
            assertFalse(evidence(first, other).matches(1, 2));
            assertFalse(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void temporalParticlesAndVerbEndingsCannotBecomeCompetingCompanies() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var prose = article(2, "한빛반도체, 월 생산 확대…푸른반도체의 고민",
                "한빛반도체, 월 7만장 생산…푸른반도체의 고민\n"
                        + "한빛반도체가 2028년 중반까지 4나노 월 생산능력을 7만장으로 늘리는 증설 목표를 제시했다. "
                        + "4나노 월 생산량은 올해 말 5만장에서 내년 중반에는 7만장으로 늘릴 예정이다. "
                        + "6나노 월 생산능력은 올해 말 8만장, 내년 중반 9만장까지 확대하는 것이 목표다.");
        assertTrue(evidence(first, prose).matches(1, 2));
    }

    @Test
    void quarterAndMonthMustAgreeExactlyAndCannotBeInventedFromPublicationTime() {
        var first = plan(1, "한빛반도체", "2028년 2분기", "4나노", "7만", "6나노", "9만");
        var same = plan(2, "한빛반도체", "내년 2분기", "4나노", "7만", "6나노", "9만");
        var changed = plan(2, "한빛반도체", "2028년 6월", "4나노", "7만", "6나노", "9만");
        var missing = plan(2, "한빛반도체", "중반", "4나노", "7만", "6나노", "9만");
        assertTrue(evidence(first, same).matches(1, 2));
        assertFalse(evidence(first, changed).matches(1, 2));
        assertFalse(evidence(first, missing).matches(1, 2));
    }

    @Test
    void oneProcessAndUnassignedRespectivelyNumbersAreInsufficient() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var single = article(2, "한빛반도체, 증산 계획", "한빛반도체는 생산능력 확대를 계획했다. 4나노 월 생산량을 내년 중반 7만장으로 확대할 계획이다.");
        var combined = article(2, "한빛반도체, 증산 계획", "한빛반도체는 생산능력 확대를 계획했다. 4나노와 6나노의 월 생산량을 내년 중반 각각 7만장과 9만장으로 확대할 계획이다.");
        assertFalse(evidence(first, single).matches(1, 2));
        assertFalse(evidence(first, combined).matches(1, 2));
    }

    @Test
    void actualProductionDoesNotTurnIntoThePlannedExpansion() {
        var first = plan(1, "한빛반도체", "올해 2분기", "4나노", "7만", "6나노", "9만");
        var actual = article(2, "한빛반도체, 실제 생산량 달성",
                "한빛반도체는 생산 실적을 공개했다. 4나노 월 생산량은 올해 2분기 7만장을 달성했다. 6나노 월 생산량은 올해 2분기 9만장을 달성했다.");
        assertFalse(evidence(first, actual).matches(1, 2));
        assertTrue(evidence(first, actual).conflicts(1, 2));
    }

    @Test
    void cancelledOrDeniedExpansionCannotSupplyPositiveOrConflictEvidence() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        for (String denial : List.of("증산 계획을 취소했다", "생산능력 확대 보도를 부인했다", "증설 계획을 추진하지 않기로 했다")) {
            var other = article(2, first.title(), first.body() + " 한빛반도체는 " + denial + ".");
            assertFalse(evidence(first, other).matches(1, 2));
            assertFalse(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void historicalExpansionCannotIdentifyAnotherCurrentHeadline() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var background = article(2, "한빛반도체, 생산시설 임대 협의", "한빛반도체는 생산시설 임대를 협의했다. 앞서 " + first.body());
        var old = article(2, first.title(), first.body().replace("4나노", "기존 계획에 따르면 4나노").replace("6나노", "기존 계획에 따르면 6나노"));
        assertFalse(evidence(first, background).matches(1, 2));
        assertFalse(evidence(first, old).matches(1, 2));
    }

    @Test
    void anotherCompanysCapacityCannotBeInheritedFromTheHeadlineOwner() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var competitor = article(2, "한빛반도체, 생산 확대를 논의", "한빛반도체는 생산 확대를 논의했다. 푸른반도체는 4나노 월 생산량을 내년 중반 7만장으로 확대할 계획이다. 푸른반도체는 6나노 월 생산량을 내년 중반 9만장으로 확대할 계획이다.");
        assertFalse(evidence(first, competitor).matches(1, 2));
        var targetBeforeCompetitor = article(2, competitor.title(), competitor.body()
                .replace("푸른반도체는 4나노 월 생산량을 내년 중반", "내년 중반 푸른반도체는 4나노 월 생산량을")
                .replace("푸른반도체는 6나노 월 생산량을 내년 중반", "내년 중반 푸른반도체는 6나노 월 생산량을"));
        assertFalse(evidence(first, targetBeforeCompetitor).matches(1, 2));
    }

    @Test
    void annualVolumesOrApproximateRangesAreNotExactMonthlyTargets() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        for (String body : List.of(first.body().replace("월 생산량", "연간 생산량"),
                first.body().replace("7만장", "약 7만장"), first.body().replace("7만장", "6~7만장"),
                first.body().replace("7만장", "7만장 이상"))) {
            assertFalse(evidence(first, article(2, first.title(), body)).matches(1, 2));
        }
    }

    @Test
    void calendarMonthCannotSupplyAMonthlyProductionUnit() {
        var first = plan(1, "한빛반도체", "2028년 6월", "4나노", "7만", "6나노", "9만");
        var annual = article(2, first.title(), first.body().replace("월 생산량", "연간 생산량"));
        var missing = article(2, first.title(), first.body().replace("월 생산량", "생산량"));
        for (var other : List.of(annual, missing)) {
            assertFalse(evidence(first, other).matches(1, 2));
            assertFalse(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void contradictoryValuesInOneArticleRemainUnknown() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var contradiction = article(2, first.title(), first.body() + " 4나노 월 생산량은 내년 중반 8만장으로 확대할 계획이다.");
        assertFalse(evidence(first, contradiction).matches(1, 2));
        assertFalse(evidence(first, contradiction).conflicts(1, 2));
    }

    @Test
    void incompleteLaterTargetsCannotBeDroppedToKeepAnEarlierMatch() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var incomplete = article(2, first.title(), first.body() + " 4나노 월 생산량은 2029년 말 10만장으로 확대할 계획이다.");
        assertFalse(evidence(first, incomplete).matches(1, 2));
    }

    @Test
    void metadataCannotVoteOrVeto() {
        var first = plan(1, "한빛반도체", "내년 중반", "4나노", "7만", "6나노", "9만");
        var metadata = new ClusterArticle(2, 1, first.title(), first.body(), null, FetchStatus.METADATA_ONLY,
                2, "fixture", BigDecimal.ONE, first.publishedAt(), first.observedAt(), List.of(), null, null, null, true);
        assertFalse(evidence(first, metadata).matches(1, 2));
        assertFalse(evidence(first, metadata).conflicts(1, 2));
    }

    private ClusterArticle ramp(long id, String finalVolume) {
        return article(id, "한빛반도체, 생산능력 증설", "한빛반도체는 생산능력 확대를 계획했다. "
                + "4나노 월 생산량은 올해 말 5만장에서 내년 중반 7만장으로 늘어날 전망이다. "
                + "6나노 월 생산량은 올해 말 8만장에서 내년 중반 " + finalVolume + "장으로 확대된다.");
    }
    private ClusterArticle plan(long id, String owner, String period, String firstNode, String firstVolume, String secondNode, String secondVolume) {
        return article(id, owner + ", 생산 확대 계획", owner + "는 생산능력 확대를 계획했다. "
                + firstNode + " 월 생산량은 " + period + " " + firstVolume + "장으로 확대할 계획이다. "
                + secondNode + " 월 생산량은 " + period + " " + secondVolume + "장으로 확대할 계획이다.");
    }
    private ProductionCapacityEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new ProductionCapacityEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }
    private ClusterArticle article(long id, String title, String body) {
        var time = OffsetDateTime.parse("2027-08-15T12:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "fixture-" + id,
                BigDecimal.ONE, time, time, List.of(), null, null, null, true);
    }
}
