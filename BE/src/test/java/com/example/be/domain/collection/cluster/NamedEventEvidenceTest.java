package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedEventEvidenceTest {
    @Test
    void joinsTheSameDatedTechnicalPartnershipWithOnlyASummaryForOneArticle() {
        var first = article(1, "새빛, 해솔컴퓨팅과 공공기관 사업 제휴",
                "새빛은 계열사 새빛교육, 반도체 기업 해솔컴퓨팅(대표 김가온)과 손잡고 시장을 확대한다. "
                        + "12일 세 회사는 NPU 기반 AX 사업 업무협약을 체결했다고 밝혔다. OS 제품을 함께 개발한다.");
        var second = summary(2, "[경제][기업소식] 새빛, NPU 기반 AX 시장 공략 나선다",
                "새빛이 계열사 새빛교육, 반도체 기업 해솔컴퓨팅과 손잡고 NPU 기반 AX 시장에 진출한다고 12일 밝혔다. "
                        + "세 회사는 OS 제품을 공동 개발한다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void aDifferentNamedPartnerVetoesSharedCompanyAndTechnology() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var second = partnership(2, "푸른컴퓨팅", "12일", "NPU AX OS");
        assertFalse(evidence(first, second).matches(1, 2));
        assertTrue(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void sharingOnePartnerCannotJoinAgreementsWithDifferentParticipantSets() {
        var first = article(1, "새빛, 기술 사업 확대",
                "새빛은 12일 해솔컴퓨팅과 푸른컴퓨팅과 손잡고 NPU AX 사업 확대를 위한 업무협약을 체결했다.");
        var second = article(2, "새빛, 기술 사업 확대",
                "새빛은 12일 해솔컴퓨팅과 별빛컴퓨팅과 손잡고 NPU AX 사업 확대를 위한 업무협약을 체결했다.");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    @Test
    void recurringPartnershipAnnouncementsOnDifferentDaysConflict() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var second = partnership(2, "해솔컴퓨팅", "11일", "NPU AX OS");
        assertFalse(evidence(first, second).matches(1, 2));
        assertTrue(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void matchingCompaniesWithoutAnExplicitDayOrSpecificScopeAreInsufficient() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var undated = partnership(2, "해솔컴퓨팅", "이날", "NPU AX OS");
        var generic = partnership(2, "해솔컴퓨팅", "12일", "AI MOU");
        assertFalse(evidence(first, undated).matches(1, 2));
        assertFalse(evidence(first, undated).conflicts(1, 2));
        assertFalse(evidence(first, generic).matches(1, 2));
    }

    @Test
    void multipleAnnouncementAndSigningDatesDoNotCreateADateConflict() {
        var first = partnership(1, "해솔컴퓨팅", "11일", "NPU AX OS");
        var second = article(2, "새빛, 기술 사업 확대",
                "새빛은 12일 해솔컴퓨팅과 손잡고 NPU AX OS 시장에 진출한다고 밝혔다. 협약은 11일 체결했다.");
        assertFalse(evidence(first, second).matches(1, 2));
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void reportingDateCannotReplaceAnExplicitRelativeSigningDate() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        for (String relative : List.of("전날", "지난주", "지난 달")) {
            var second = article(2, "새빛, 기술 사업 확대",
                    "새빛은 12일 해솔컴퓨팅과 " + relative + " 체결한 NPU AX OS 업무협약을 발표했다.");
            assertFalse(evidence(first, second).matches(1, 2));
            assertFalse(evidence(first, second).conflicts(1, 2));
        }
    }

    @Test
    void anOldPartnershipInBackgroundDoesNotIdentifyTheCurrentProductReport() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var second = article(2, "새빛, NPU 기반 AX 신제품 공개",
                "새빛은 OS 제품을 출시했다. 앞서 새빛은 12일 해솔컴퓨팅과 손잡고 NPU AX 사업에 진출했다.");
        assertFalse(evidence(first, second).matches(1, 2));
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void aSubsidiarysPartnershipCannotStandInForItsHeadlineParent() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var second = article(2, "새빛, NPU 기반 AX 시장 공략",
                "새빛교육은 12일 해솔컴퓨팅과 손잡고 NPU AX OS 교육 과정을 개발한다.");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    @Test
    void aProductLaunchHeadlineDoesNotInheritAPartnershipFromItsLead() {
        var first = partnership(1, "해솔컴퓨팅", "12일", "NPU AX OS");
        var second = article(2, "새빛, NPU 기반 신제품 출시",
                "새빛은 OS 제품을 출시했다. 새빛은 12일 해솔컴퓨팅과 손잡고 NPU AX 사업을 확대했다.");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    @Test
    void joinsTheSameAnniversaryReportWithAnInstitutionAliasAndAQuotedHeadline() {
        var first = article(1, "미래AI위원회, 지역 전환 실행 속도 높인다",
                "김가온 미래인공지능위원회 부위원장이 12일 시민회관에서 열린 출범 3주년 성과보고회에서 발표했다.");
        var second = article(2, "\"지역 대전환 기틀 마련…더 큰 도약 만들 때\"",
                "\"지역 대전환의 기틀을 마련했고 더 큰 도약을 만들 때입니다.\" 김가온 미래AI위원회 부위원장은 "
                        + "12일 시민회관에서 열린 출범 3주년 성과보고회에서 지역 실행 계획을 설명했다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void anAnniversaryEventOnAnotherDayOrAtAnotherEditionConflicts() {
        var first = anniversary(1, "미래AI위원회", "12일", "3");
        var anotherDay = anniversary(2, "미래AI위원회", "11일", "3");
        var anotherEdition = anniversary(2, "미래AI위원회", "12일", "4");
        assertFalse(evidence(first, anotherDay).matches(1, 2));
        assertTrue(evidence(first, anotherDay).conflicts(1, 2));
        assertFalse(evidence(first, anotherEdition).matches(1, 2));
        assertTrue(evidence(first, anotherEdition).conflicts(1, 2));
    }

    @Test
    void theSameAnniversaryOrdinalAndDateDoNotIdentifyAnInstitution() {
        var first = anniversary(1, "미래AI위원회", "12일", "3");
        var second = anniversary(2, "지역AI위원회", "12일", "3");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    @Test
    void aGuestCouncilCannotBecomeTheHostOfAnotherCouncilsAnniversary() {
        var guest = article(1, "미래AI위원회, 정책 교류 확대",
                "미래AI위원회 부위원장은 12일 지역AI위원회 출범 3주년 성과보고회에 참석했다.");
        var ownEvent = anniversary(2, "미래AI위원회", "12일", "3");
        var differentEdition = anniversary(2, "미래AI위원회", "12일", "4");
        assertFalse(evidence(guest, ownEvent).matches(1, 2));
        assertFalse(evidence(guest, ownEvent).conflicts(1, 2));
        assertFalse(evidence(guest, differentEdition).conflicts(1, 2));
    }

    @Test
    void anInstitutionInTheHeadlineDoesNotMakeALaterBackgroundAnniversaryPrimary() {
        var first = anniversary(1, "미래AI위원회", "12일", "3");
        var second = article(2, "미래AI위원회, 연구비 삭감 계획 철회",
                "미래AI위원회는 연구비 계획을 변경했다. 한편 미래AI위원회는 12일 출범 3주년 성과보고회를 열었다.");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    @Test
    void aSharedCouncilDoesNotJoinDifferentDailyPolicyReports() {
        var first = anniversary(1, "미래AI위원회", "12일", "3");
        var second = article(2, "미래AI위원회, 로봇 연구 지원 방안 발표",
                "미래AI위원회는 12일 로봇 연구 지원 방안을 발표했다.");
        assertFalse(evidence(first, second).matches(1, 2));
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void multipleUniversitiesInIndependentProgramsAreNotForcedIntoASingleNamedEvent() {
        var first = article(1, "새빛대·해솔대, 국책사업 참여",
                "새빛대와 해솔대가 국책사업에 각각 참여한다. 새빛대는 교육부의 지역 성장 인재 육성 사업에 선정됐다. "
                        + "해솔대는 과학기술부의 사이버 보안 모델 개발 사업에 참여한다.");
        var second = article(2, "새빛대, 지역 인재 육성 사업 선정",
                "새빛대는 교육부가 12일 발표한 지역 성장 인재 육성 사업에 선정됐다.");
        assertFalse(evidence(first, second).matches(1, 2));
    }

    private ClusterArticle partnership(long id, String partner, String day, String scope) {
        return article(id, "새빛, 기술 사업 확대", "새빛은 " + day + " " + partner
                + "과 손잡고 " + scope + " 사업 확대를 위한 업무협약을 체결했다.");
    }

    private ClusterArticle anniversary(long id, String council, String day, String ordinal) {
        return article(id, council + ", 정책 실행 속도 높인다",
                "김가온 " + council + " 부위원장이 " + day + " 시민회관에서 열린 출범 "
                        + ordinal + "주년 성과보고회에서 발표했다.");
    }

    private NamedEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new NamedEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }

    private ClusterArticle article(long id, String title, String body) {
        return fixture(id, title, null, body);
    }

    private ClusterArticle summary(long id, String title, String summary) {
        return fixture(id, title, summary, null);
    }

    private ClusterArticle fixture(long id, String title, String summary, String body) {
        var time = OffsetDateTime.parse("2026-04-12T12:00:00+09:00");
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT, id, "fixture-" + id,
                new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }
}
