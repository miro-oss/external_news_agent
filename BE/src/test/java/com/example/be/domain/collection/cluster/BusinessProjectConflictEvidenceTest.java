package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessProjectConflictEvidenceTest {
    @Test
    void distinctHeadlinePartnersAndActionsConflictWithoutAnOrganizationDictionary() {
        assertTrue(evidence(development(1), production(2, "푸른모터스")).conflicts(1, 2));
        assertFalse(evidence(development(1), production(2, "새빛설계")).conflicts(1, 2));
        var reversed = article(1, development(1).title(),
                "새빛설계와 반도체 제조기업 한빛전자는 첨단 반도체 플랫폼을 개발하고 있다.");
        assertTrue(evidence(reversed, production(2, "푸른모터스")).conflicts(1, 2));
        assertFalse(evidence(reversed, production(2, "새빛설계")).conflicts(1, 2));
    }

    @Test
    void recognizedMakerAliasesAndTickerDoNotHideTheProject() {
        var joint = article(1, "삼성·누리설계 3나노 동맹 칩 개발",
                "삼성전자와 AI 반도체 설계기업 누리설계는 3나노 공정을 활용해 엣지 AI 플랫폼을 개발하고 있다.");
        var production = article(2, "[단독] 삼성 공장 본격 가동 누리모터스칩 만든다",
                "삼성전자(005930)가 누리모터스의 인공지능 칩 공급을 위해 미국 공장에서 시제품 생산에 돌입했다.");
        assertTrue(evidence(joint, production).conflicts(1, 2));
    }

    @Test
    void backgroundAndUnnamedHeadlinePartnersDoNotCreateConflicts() {
        var production = production(2, "푸른모터스");
        for (var unclear : List.of(
                article(1, "한빛전자 칩 개발 사업 확대", development(1).body()),
                article(1, development(1).title(), "한빛전자는 올해 실적을 발표했다. 한편 " + development(1).body()),
                article(1, development(1).title(), "한빛전자와 새빛설계는 플랫폼을 개발할 가능성이 있다."),
                article(1, development(1).title(), "한빛전자와 새빛설계는 플랫폼 개발설에 답하지 않았다."))) {
            assertFalse(evidence(unclear, production).conflicts(1, 2));
        }
    }

    @Test
    void summaryAndMetadataCannotSupplyTheMissingBodyProfile() {
        var body = development(1);
        var metadata = new ClusterArticle(1, 1, body.title(), body.body(), null, FetchStatus.FETCH_FAILED,
                1, "publisher", BigDecimal.ONE, body.publishedAt(), body.observedAt(), List.of(),
                null, null, null, true);
        assertFalse(evidence(metadata, production(2, "푸른모터스")).conflicts(1, 2));
    }

    @Test
    void deniedAndCancelledActionsDoNotCreateProjectConflicts() {
        for (String predicate : List.of("생산하고 있지 않다고 밝혔다", "시험 생산을 시작하지 않기로 결정했다",
                "시험 생산을 시작한다는 보도를 부인했다", "시험 생산 개시 계획을 취소했다")) {
            var denied = article(2, production(2, "푸른모터스").title(),
                    "한빛전자는 푸른모터스의 인공지능 칩을 " + predicate + ".");
            assertFalse(evidence(development(1), denied).conflicts(1, 2));
        }
        var denied = article(1, development(1).title(),
                "한빛전자와 새빛설계는 반도체 플랫폼을 개발한다는 보도를 부인했다.");
        assertFalse(evidence(denied, production(2, "푸른모터스")).conflicts(1, 2));
    }

    @Test
    void anotherSubjectsActionCannotEstablishTheHeadlinePartiesProject() {
        var funding = article(1, development(1).title(),
                "한빛전자와 새빛설계는 지원금을 제공하고 연구팀은 반도체 플랫폼을 개발했다.");
        assertFalse(evidence(funding, production(2, "푸른모터스")).conflicts(1, 2));
        var visit = article(2, production(2, "푸른모터스").title(),
                "한빛전자는 푸른모터스의 공장을 방문했고 누리공업은 인공지능 칩을 생산했다.");
        assertFalse(evidence(development(1), visit).conflicts(1, 2));
    }

    @Test
    void conflictingActionsBlockGenericTitleVotesAndUnknownTransitiveBridges() {
        var first = development(1);
        var second = production(2, "푸른모터스");
        var bridge = article(3, "한빛전자 첨단 반도체 칩 사업 확장", "첨단 반도체 칩 사업에 대한 소식이다.");
        var properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0.3);
        var plan = new IssueClusterer(properties, new BreakingNewsDetector())
                .cluster(List.of(first, second, bridge), true);
        assertTrue(plan.pairScores().stream().allMatch(pair -> pair.titleJaccard() >= 0.3));
        assertEquals(2, plan.issues().size());
        assertTrue(plan.issues().stream().noneMatch(issue ->
                issue.articleIds().contains(1L) && issue.articleIds().contains(2L)));
    }

    private static BusinessProjectConflictEvidence evidence(ClusterArticle... articles) {
        return new BusinessProjectConflictEvidence(List.of(articles), new BreakingNewsDetector());
    }

    private static ClusterArticle development(long id) {
        return article(id, "한빛전자 새빛설계 첨단 반도체 칩 사업 확장",
                "한빛전자와 반도체 설계기업 새빛설계는 첨단 반도체 플랫폼을 개발하고 있다.");
    }

    private static ClusterArticle production(long id, String customer) {
        return article(id, "한빛전자 " + customer + " 첨단 반도체 칩 사업 확장",
                "한빛전자는 " + customer + "의 인공지능 칩을 위한 시험 생산에 돌입했다.");
    }

    private static ClusterArticle article(long id, String title, String body) {
        var time = OffsetDateTime.parse("2026-07-09T09:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "publisher",
                BigDecimal.ONE, time, time, List.of(), null, null, null, true);
    }
}
