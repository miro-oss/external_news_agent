package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcreteOccurrenceEvidenceTest {
    @Test
    void joinsDifferentAnglesOfADatedHostedOccasionWithAnOptionalHostPrefix() {
        var first = article(1, "새빛, 제조 데이터 처리 개선", "새빛 AI연구원은 14일 마곡 새빛사이언스파크에서 '새빛 AI 토크 콘서트 2026'를 개최했다. 제조 성과를 공개했다.");
        var second = article(2, "새빛, 과학 난제 해결 집중", "14일 마곡 새빛사이언스파크에서 열린 'AI 토크 콘서트 2026'에서 새빛 AI연구원이 과학 연구 성과를 소개했다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void aLaterNamedOccurrenceCanReferToTheUniqueEarlierWrittenDay() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        var second = article(2, "새빛, 공정 예측 시간 단축",
                "새빛 AI연구원이 제조 AI를 소개했다. 14일 관련업계에 따르면 현장 적용을 확대하고 있다.\n"
                        + "현장 데이터의 품질과 검증 결과를 설명했다. ".repeat(48)
                        + "\n새빛 AI연구원은 이날 새빛사이언스파크에서 열린 'AI 토크 콘서트 2026'에서 공정 예측 성과를 공개했다.");
        assertTrue(second.body().indexOf("토크 콘서트") > 1200);
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void aPublicationDateNeverFillsAnUndatedOccasion() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        var undated = occasion(2, "새빛", "이날", "2026", "새빛사이언스파크");
        assertFalse(evidence(first, undated).matches(1, 2));
        assertFalse(evidence(first, undated).conflicts(1, 2));
    }

    @Test
    void differentOccasionDaysHostsEditionsAndKnownVenuesConflict() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        for (var other : List.of(occasion(2, "새빛", "13일", "2026", "새빛사이언스파크"),
                occasion(2, "해솔", "14일", "2026", "새빛사이언스파크"),
                occasion(2, "새빛", "14일", "2025", "새빛사이언스파크"),
                occasion(2, "새빛", "14일", "2026", "해솔컨벤션센터"))) {
            assertFalse(evidence(first, other).matches(1, 2));
            assertTrue(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void anUnrelatedHeadlineCannotInheritAnOccasionAndGuestsCannotReplaceTheHost() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        var unrelated = article(2, "해솔, 신규 공장 준공", first.body());
        var guest = article(2, "새빛, 기술 교류 확대", "새빛 AI연구원은 해솔 AI연구원이 14일 새빛사이언스파크에서 개최한 'AI 토크 콘서트 2026'에 참석했다.");
        assertFalse(evidence(first, unrelated).matches(1, 2));
        assertFalse(evidence(first, guest).matches(1, 2));
    }

    @Test
    void backgroundOrMultipleOccurrencesDoNotSupplyOneIdentity() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        var background = article(2, "새빛, 공정 기술 공개", "새빛 AI연구원은 새로운 제품을 공개했다. 앞서 " + first.body());
        var multiple = article(2, "새빛, 공정 기술 공개", first.body() + " 새빛 AI연구원은 13일 같은 장소에서 '소재 기술 포럼 2026'를 개최했다.");
        var ambiguousDay = article(2, "새빛, 공정 기술 공개", "새빛 AI연구원은 13일 기술을 소개했다. 14일 계획을 발표했다. 새빛 AI연구원은 이날 'AI 토크 콘서트 2026'에서 성과를 공개했다.");
        for (var other : List.of(background, multiple, ambiguousDay)) assertFalse(evidence(first, other).matches(1, 2));
    }

    @Test
    void publicCallLinksPolicyRemarksAndDeviceUsageWithoutSharedHeadlineTopics() {
        var first = article(1, "최가온, 로봇 위험론 반박", "최가온 가온국 대통령과 김해솔 해솔전자 최고경영자(CEO)가 기술 전망을 설명했다. 최가온 대통령은 14일 김해솔 CEO가 참석한 '미래 서밋' 도중 전화해 산업 발전을 강조했다.");
        var second = article(2, "김해솔, 최신 접는 스마트폰 사용", "김해솔 해솔전자 CEO가 새로운 스마트폰을 사용했다. 김해솔 CEO는 14일 '미래 서밋(Future Summit) 2026' 무대에서 최가온 가온국 대통령의 전화를 받았다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void theCallUsesTheOccurrenceDateAfterAnExplicitReportingPrefix() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var second = article(2, "김해솔, 새로운 기기 사용", "김해솔 해솔전자 CEO가 신제품을 사용했다. 15일 업계에 따르면 김해솔 CEO는 14일 '미래 서밋' 무대에서 최가온 가온국 대통령의 전화를 받았다. 행사 전체는 13일부터 15일까지 진행된다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void explicitDifferentCallDateParticipantOrOccasionConflicts() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        for (var other : List.of(call(2, "13일", "최가온", "미래 서밋", "김해솔"), call(2, "14일", "박푸른", "미래 서밋", "김해솔"), call(2, "14일", "최가온", "혁신 서밋", "김해솔"))) {
            assertFalse(evidence(first, other).matches(1, 2));
            assertTrue(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void inPersonOrUndatedPrivateCallsAreNotPublicCallEvidence() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var meeting = article(2, "김해솔, 대통령과 기술 협의", first.body().replace("전화를 받았다", "직접 만났다"));
        var privateCall = article(2, "김해솔, 대통령과 기술 협의", first.body().replace("'미래 서밋' 무대에서 ", ""));
        var undated = call(2, "이날", "최가온", "미래 서밋", "김해솔");
        for (var other : List.of(meeting, privateCall, undated)) assertFalse(evidence(first, other).matches(1, 2));
    }

    @Test
    void repeatedCallsOnTwoDaysAreNotAnArbitrarilyChosenOccurrence() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var multiple = article(2, "김해솔, 대통령과 기술 협의", first.body() + " 김해솔 CEO는 13일 '미래 서밋' 무대에서 최가온 대통령의 전화를 받았다.");
        assertFalse(evidence(first, multiple).matches(1, 2));
    }

    @Test
    void oldCallsAndPhotoCaptionsCannotIdentifyTheCurrentReport() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var background = article(2, "김해솔, 새로운 투자 계획", "김해솔 해솔전자 CEO는 투자를 발표했다. 한편 " + first.body());
        var caption = article(2, "김해솔, 새로운 투자 계획", first.body().replace(". ", ".\n\n").replace("받았다.", "받는 모습 (사진=현장 제공)."));
        var historical = article(2, "김해솔, 새로운 투자 계획", first.body().replace("14일", "지난 3월 14일"));
        for (var other : List.of(background, caption, historical)) assertFalse(evidence(first, other).matches(1, 2));
    }

    @Test
    void sourceWithoutVerifiedBodyCannotVoteOrVeto() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var metadata = new ClusterArticle(2, 1, first.title(), first.body(), null, FetchStatus.METADATA_ONLY, 2L, "fixture", BigDecimal.ONE, first.publishedAt(), first.observedAt(), List.of(), null, null, null, true);
        assertFalse(evidence(first, metadata).matches(1, 2));
        assertFalse(evidence(first, metadata).conflicts(1, 2));
    }

    @Test
    void aPhotoCreditAfterTheSentenceAlsoExcludesTheCaptionParagraph() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var caption = article(2, "김해솔, 새로운 투자 계획", first.body().replace(". ", ".\n\n")
                + " (사진=현장 제공)\n\n김해솔 해솔전자 CEO는 새로운 투자 계획을 설명했다.");
        assertFalse(evidence(first, caption).matches(1, 2));
    }

    @Test
    void unnumberedOccasionsStillHaveACompleteNamedDatedIdentity() {
        var first = article(1, "새빛, 기술 성과 공개", "새빛 AI연구원은 14일 'AI 토크 콘서트'를 개최했다.");
        var second = article(2, "새빛, 산업 과제 설명", "새빛 AI연구원은 14일 'AI 토크 콘서트'에서 성과를 공개했다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void missingMonthsAcrossAMonthBoundaryAreUnknownInsteadOfInventingAPreviousMonth() {
        var first = occasion(1, "새빛", "3월 31일", "2026", "새빛사이언스파크");
        var omittedMonth = occasion(2, "새빛", "31일", "2026", "새빛사이언스파크");
        assertFalse(evidence(first, omittedMonth).matches(1, 2));
        assertFalse(evidence(first, omittedMonth).conflicts(1, 2));
    }

    @Test
    void aUniqueIntroducedForeignNameSupportsItsShortRoleAlias() {
        var first = article(1, "로버트 스톤, 산업 투자 강조", "로버트 스톤 가온국 대통령과 제이미 킴 미래전자 최고경영자(CEO)가 의견을 냈다. 스톤 대통령은 14일 킴 CEO가 참석한 '미래 서밋' 도중 전화해 산업 투자를 강조했다.");
        var second = article(2, "제이미 킴, 접는 기기 사용", "제이미 킴 미래전자 CEO가 기기를 사용했다. 킴 CEO는 14일 '미래 서밋' 무대에서 로버트 스톤 가온국 대통령의 전화를 받았다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void ambiguousSurnamesAndPlannedCallsCannotSupplyParticipantsOrACompletedOccurrence() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var planned = article(2, "김해솔, 대통령 통화 계획", first.body().replace("전화를 받았다", "전화를 받을 계획이다"));
        assertFalse(evidence(first, planned).matches(1, 2));
        var named = article(1, "제이미 킴, 산업 전망 설명", "제이미 킴 미래전자 CEO와 로버트 스톤 가온국 대통령이 의견을 냈다. 킴 CEO는 14일 '미래 서밋' 무대에서 스톤 대통령의 전화를 받았다.");
        var ambiguous = article(2, "제이미 킴, 기기 사용", "제이미 킴 미래전자 CEO와 알렉스 킴 푸른전자 CEO, 로버트 스톤 가온국 대통령이 참석했다. 킴 CEO는 14일 '미래 서밋' 무대에서 스톤 대통령의 전화를 받았다.");
        assertFalse(evidence(named, ambiguous).matches(1, 2));
    }

    @Test
    void aDeniedPhoneCallIsNotAnOccurrence() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var denied = article(2, first.title(), first.body().replace("전화를 받았다", "전화를 받았다는 보도를 부인했다"));
        assertFalse(evidence(first, denied).matches(1, 2));
        assertFalse(evidence(first, denied).conflicts(1, 2));
    }

    @Test
    void aRequestToPhoneIsNotACompletedPhoneCall() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var requested = article(2, first.title(), first.body().replace("대통령의 전화를 받았다", "대통령에게 전화해 달라고 요청했다"));
        assertFalse(evidence(first, requested).matches(1, 2));
    }

    @Test
    void anUnansweredRequestDoesNotBecomeAPhoneCall() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var unanswered = article(2, first.title(), first.body().replace("대통령의 전화를 받았다", "대통령에게 전화해 달라고 요청했지만 응답을 얻지 못했다"));
        assertFalse(evidence(first, unanswered).matches(1, 2));
    }

    @Test
    void cancelledAndPlannedHostedEventsCannotIdentifyAHeldOccasion() {
        var first = occasion(1, "새빛", "14일", "2026", "새빛사이언스파크");
        for (String replacement : List.of("개최 계획을 취소했다", "개최하지 않기로 결정했다", "개최할 예정이다")) {
            var other = article(2, first.title(), first.body().replace("개최했다", replacement));
            assertFalse(evidence(first, other).matches(1, 2));
            assertFalse(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void quotedPolicyNegationDoesNotDenyTheSurroundingCompletedCall() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        var quoted = article(2, first.title(), first.body().replace("전화를 받았다", "전화를 받고 \"로봇이 세상을 장악하지 않을 것이다. 산업을 멈출 계획은 없다\"고 말했다"));
        assertTrue(evidence(first, quoted).matches(1, 2));
    }

    @Test
    void aDurationIsNotAnExplicitOccurrenceDay() {
        var first = call(1, "14일", "최가온", "미래 서밋", "김해솔");
        for (String duration : List.of("14일간", "14일째", "14일 동안", "14일 만에")) {
            var other = article(2, first.title(), first.body().replace("14일 ", "")
                    .replace("전화를 받았다", "전화를 받았고 " + duration + " 논의한 과제를 설명했다"));
            assertFalse(evidence(first, other).matches(1, 2));
            assertFalse(evidence(first, other).conflicts(1, 2));
        }
    }

    @Test
    void aPossessiveVenueModifierCannotIntroduceAnExtraParticipant() {
        var first = article(1, "최가온, 로봇 위험론 반박", "최가온 가온국 대통령과 김해솔 해솔전자 CEO가 기술을 논의했다. 최가온 대통령은 14일 '미래 서밋' 무대의 김해솔 CEO에게 전화했다.");
        var second = article(2, "김해솔, 접는 스마트폰으로 눈길", "김해솔 해솔전자 CEO는 14일 '미래 서밋' 무대에서 최가온 가온국 대통령의 전화를 받았다.");
        assertTrue(evidence(first, second).matches(1, 2));
    }

    @Test
    void proseBeforeAMultiwordAcronymHostCannotBecomePartOfItsName() {
        var first = article(1, "AB, 현장 성과 공개", "AB AI연구원은 14일 'AB AI 토크 콘서트 2026'를 개최했다.");
        for (String opening : List.of("현장의 과제를 해결하는 것이 AB AI연구원의 임무다.",
                "14일 업계에 따르면 AB AI연구원이 성과를 소개했다.",
                "산업 환경이 변하는 상황에서도 AB AI연구원이 성과를 공개했다.",
                "산업 성과를 공개했다.\n\nAB AI연구원이 현장 과제를 소개했다.")) {
            var other = article(2, "AB, 산업용 모델 공개", opening
                    + "\n\n14일 열린 'AI 토크 콘서트 2026'에서 AB AI연구원이 산업 성과를 공개했다.");
            assertTrue(evidence(first, other).matches(1, 2));
        }
    }

    private ClusterArticle occasion(long id, String host, String day, String edition, String venue) {
        return article(id, host + ", 현장 적용 성과 공개", host + " AI연구원은 " + day + " " + venue + "에서 'AI 토크 콘서트 " + edition + "'를 개최했다.");
    }
    private ClusterArticle call(long id, String day, String president, String occasion, String executive) {
        return article(id, executive + ", 새로운 기기 사용", executive + " 해솔전자 CEO가 신제품을 사용했다. " + executive + " CEO는 " + day + " '" + occasion + "' 무대에서 " + president + " 가온국 대통령의 전화를 받았다.");
    }
    private ConcreteOccurrenceEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new ConcreteOccurrenceEvidence(List.of(first, second), new BreakingNewsDetector());
    }
    private ClusterArticle article(long id, String title, String body) {
        var time = OffsetDateTime.parse("2026-04-15T12:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "fixture-" + id, BigDecimal.ONE, time, time, List.of(), null, null, null, true);
    }
}
