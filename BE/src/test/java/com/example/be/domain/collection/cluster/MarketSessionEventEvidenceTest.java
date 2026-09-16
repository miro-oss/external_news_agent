package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSessionEventEvidenceTest {
    @Test
    void differentHeadlinesAndSectorDirectionsCanDescribeOneIndexClose() {
        var first = article(1, "뉴욕증시, 유가 부담에 하락", "17일(현지시간) 뉴욕증시에서 다우지수는 0.3% 내린 42000에 거래를 마쳤다.");
        var second = article(2, "미국 증시, 기술주 약세…반도체는 반등",
                "미국 뉴욕증시는 17일(현지시간) 기술주 약세를 보였다. 나스닥 종합지수는 18000에 하락 마감했다. 반도체지수는 올랐다.");
        assertMatch(first, second);
    }

    @Test
    void dateOrderAndLocalTimeAliasesIdentifyTheExchange() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 나스닥지수는 하락 마감했다.");
        for (String text : List.of(
                "17일(미국시간) 뉴욕증권거래소(NYSE)에서 다우지수는 하락 마감했다.",
                "17일(이하 미국시간) 미국 뉴욕증시에서 다우지수는 하락 마감했다.",
                "17일(미 동부시간) 미국증시에서 다우지수는 하락 마감했다.",
                "17일(이하 미 동부시각) 뉴욕증권거래소에서 나스닥은 거래를 마쳤다.",
                "17일(미국 동부 시간) 뉴욕증시에서 다우지수는 하락 마감했다.",
                "미국증시는 17일(현지시각) 하락했다. S&P500지수는 거래를 마쳤다.",
                "17일(현지시간) NYSE에서 나스닥100 지수는 거래를 마쳤다.")) {
            assertMatch(first, article(2, "美 나스닥100 하락", text));
        }
    }

    @Test
    void marketMoveSynonymsAndExplicitMarketColumnsStillRequireADatedActualClose() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        for (String title : List.of("뉴욕증시 폭락", "미국 증시 돌파 후 숨 고르기", "[뉴욕증시] 기술주 직격탄")) {
            assertMatch(close, article(2, title, "17일(미 동부시간) 미국증시에서 나스닥은 거래를 마쳤다."));
            assertUnrelated(close, article(2, title, "17일(미 동부시간) 미국증시에서 나스닥은 장중 약세를 보였다."));
            assertUnrelated(close, article(2, title, "미국증시에서 나스닥은 거래를 마쳤다."));
        }
        assertUnrelated(close, article(2, "[뉴욕증시] 기업 신제품 출시",
                "17일 뉴욕증시에서 나스닥은 거래를 마쳤다."));
        assertUnrelated(close, article(2, "[뉴욕증시] 산업 수요 전망",
                "17일 뉴욕증시에서 나스닥은 거래를 마쳤다."));
    }

    @Test
    void closingEvidenceCanFollowIntroductoryParagraphsWithinTheBoundedLead() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        var second = article(2, "미국 증시 약세",
                "시장 참가자들은 채권과 에너지 가격에 주목했다.\n".repeat(8)
                        + "17일(현지시간) 미국증시는 약세였다.\nS＆P 500지수는 전일 대비 0.4% 내린 5500에 마감했다.");
        assertMatch(first, second);
    }

    @Test
    void differentSessionsConflictSymmetricallyButOtherMarketsDoNot() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        var previous = article(2, "미국 증시 하락", "16일 미국증시에서 나스닥은 하락 마감했다.");
        var pair = evidence(first, previous);
        assertFalse(pair.matches(1, 2));
        assertTrue(pair.conflicts(1, 2));
        assertTrue(pair.conflicts(2, 1));
        for (var other : List.of(
                article(2, "코스피 하락", "17일 코스피는 하락 마감했다."),
                article(2, "코스닥 하락", "17일 코스닥지수는 하락 마감했다."),
                article(2, "중국증시 하락 마감", "17일 중국증시에서 상하이지수는 하락 마감했다."))) {
            assertUnrelated(first, other);
        }
    }

    @Test
    void companyAndNonClosingHeadlinesCannotInheritMarketBackground() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        String body = "17일 뉴욕증시에서 다우지수는 하락 마감했다. 한편 개별 기업의 신제품이 공개됐다.";
        for (String title : List.of(
                "[美증시 특징주] 반도체 기업, 합병에 급등",
                "뉴욕증시 약세에도 엔비디아 주가 강세",
                "뉴욕증시 관심 집중…신제품 출시",
                "뉴욕증시 상승 출발", "뉴욕증시 장중 하락", "뉴욕증시 시간외 상승",
                "뉴욕증시 상승 전망", "미국 증시 지수 편입에 급등",
                "코스피와 뉴욕증시 하락 마감")) {
            assertUnrelated(first, article(2, title, body));
        }
    }

    @Test
    void anActualIndexCloseIsRequiredAndCannotComeFromOtherInstrumentsOrYesterday() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        for (String text : List.of(
                "17일 뉴욕증시에서 나스닥은 장중 하락했다.",
                "17일 뉴욕증시에서 나스닥은 하락 마감할 것으로 전망했다.",
                "17일 뉴욕증시에서 나스닥은 상승했다. 뉴욕상업거래소에서 원유는 하락 마감했다.",
                "17일 뉴욕증시에서 나스닥이 하락한 뒤 원유 선물은 상승 마감했다.",
                "17일 뉴욕증시는 약세였다. 전날 나스닥은 하락 마감했다.",
                "17일 뉴욕증시는 약세였다. 16일 나스닥은 하락 마감했다.",
                "17일 뉴욕증시는 약세였다. 한편 나스닥은 하락 마감했다.")) {
            assertUnrelated(first, article(2, "뉴욕증시 하락", text));
        }
    }

    @Test
    void datesMustBeAttachedToTheMarketAndConsistent() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        for (String text : List.of(
                "뉴욕증시에서 다우지수는 하락 마감했다.",
                "17일 보고서를 발표했다. 뉴욕증시에서 다우지수는 하락 마감했다.",
                "17일 뉴욕증시에서 다우지수는 하락 마감했다. 16일 뉴욕증시는 약세였다.",
                "19일 뉴욕증시에서 다우지수는 하락 마감했다.",
                "32일 뉴욕증시에서 다우지수는 하락 마감했다.",
                "지난달 17일 뉴욕증시에서 다우지수는 하락 마감했다.",
                "2025년 6월 17일 뉴욕증시에서 다우지수는 하락 마감했다.")) {
            assertUnrelated(first, article(2, "뉴욕증시 하락", text));
        }
    }

    @Test
    void elapsedDaysAndStreakLengthsAreNotCalendarDates() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        for (String duration : List.of("17일간", "17일째", "17일동안", "17일 동안", "17일 연속")) {
            assertUnrelated(close, article(2, "뉴욕증시 하락", "뉴욕증시는 " + duration
                    + " 약세였다. 나스닥은 하락 마감했다."));
        }
    }

    @Test
    void publicationCalendarResolvesAnExplicitDayAcrossMonthAndYearBoundaries() {
        for (String reportedAt : List.of("2026-07-01T09:00:00+09:00", "2027-01-01T09:00:00+09:00")) {
            boolean newYear = reportedAt.startsWith("2027");
            String day = newYear ? "31" : "30";
            String fullDate = newYear ? "2026년 12월 31일" : "2026년 6월 30일";
            var first = article(1, "뉴욕증시 하락", day + "일 뉴욕증시에서 다우지수는 하락 마감했다.", reportedAt);
            var second = article(2, "미국 증시 약세", fullDate + " 미국증시에서 나스닥은 하락 마감했다.", reportedAt);
            assertMatch(first, second);
        }
    }

    @Test
    void invalidCalendarDatesAndUnrelatedReportTimesAreNotGuessed() {
        var first = article(1, "뉴욕증시 하락", "28일 뉴욕증시에서 다우지수는 하락 마감했다.", "2027-03-01T09:00:00+09:00");
        assertUnrelated(first, article(2, "뉴욕증시 하락", "2월 29일 뉴욕증시에서 나스닥은 하락 마감했다.", "2027-03-01T09:00:00+09:00"));
        assertUnrelated(first, article(2, "뉴욕증시 하락", "28일 뉴욕증시에서 나스닥은 하락 마감했다.", "2027-03-04T09:00:00+09:00"));
    }

    @Test
    void leapDayIsResolvedFromAnExplicitTradingDay() {
        var first = article(1, "뉴욕증시 하락", "29일 뉴욕증시에서 다우지수는 하락 마감했다.", "2028-03-01T09:00:00+09:00");
        var second = article(2, "미국 증시 하락", "2028년 2월 29일 미국증시에서 나스닥은 하락 마감했다.", "2028-03-01T10:00:00+09:00");
        assertMatch(first, second);
    }

    @Test
    void sameTradingDateDoesNotBypassTheAbsoluteReportTimeWindow() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.", "2026-06-17T00:00:00+09:00");
        var second = article(2, "미국 증시 하락", "17일 미국증시에서 나스닥은 하락 마감했다.", "2026-06-19T09:00:00+09:00");
        assertUnrelated(first, second);
    }

    @Test
    void metadataOrLateBodyBackgroundCannotCreateOrVetoSessionIdentity() {
        var first = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        OffsetDateTime time = first.eventTime();
        var metadata = new ClusterArticle(2, 1, "뉴욕증시 하락", "16일 뉴욕증시에서 나스닥은 하락 마감했다.", null,
                FetchStatus.METADATA_ONLY, 2, "fixture", null, time, time, List.of(), null, null, null, true);
        assertUnrelated(first, metadata);
        assertUnrelated(first, article(2, "미국 증시 약세", "시장 분석. ".repeat(400)
                + "17일 뉴욕증시에서 나스닥은 하락 마감했다."));
    }

    @Test
    void industryDemandOutlookCannotJoinADatedCloseThroughAnotherLexicalEdge() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        String title = "기술주 출렁…산업 비관론은 아직";
        var outlook = article(2, title, title + "\n등록 2026.06.18 09:00\n작게\n크게\n"
                + "지난달 열린 기술 박람회에서 관람객들이 전시장을 둘러봤다. (사진=주최측 제공)\n"
                + "기술 개발 논쟁이 기업의 사업에 미칠 영향에도 관심이 쏠린다.\n"
                + "전날 주요 기업의 주가가 하락했지만 이미 확보한 주문은 수요의 버팀목이다.");
        var pair = evidence(close, outlook);
        assertFalse(pair.matches(1, 2));
        assertTrue(pair.conflicts(1, 2));
        assertTrue(pair.conflicts(2, 1));
    }

    @Test
    void outlookVetoRequiresBothTheTitleAndThePrimaryReportingSentence() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        assertUnrelated(close, article(2, "기술 기업 소식",
                "산업 변화가 기업의 사업에 미칠 영향에도 관심이 쏠린다."));
        assertUnrelated(close, article(2, "산업 수요 전망 진단",
                "기업은 오늘 새로운 제품을 출시했다.\n산업 변화가 사업에 미칠 영향에도 관심이 쏠린다."));
        assertUnrelated(close, article(2, "산업 비관론은 아직",
                "새로운 기술을 둘러싼 논쟁이 확산하고 있다.\n수요 감소 가능성을 분석하고 있다."));
        assertUnrelated(close, article(2, "산업 수요 둔화 우려", "업계 관계자들이 행사에 참석했다."));
    }

    @Test
    void observedClosingReportsKeepTheirIdentityEvenWithAnOutlookBackground() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        var second = article(2, "미국 증시 하락", "17일 미국증시에서 나스닥은 하락 마감했다.\n"
                + "산업 수요 감소 가능성을 분석한 보고서도 나왔다.");
        assertMatch(close, second);
        // A mixed headline can remain unknown without acquiring a false outlook veto.
        assertUnrelated(close, article(2, "뉴욕증시 하락…산업 수요 전망 관심",
                "산업 변화가 기업의 사업에 미칠 영향에도 관심이 쏠린다.\n"
                        + "17일 뉴욕증시에서 나스닥은 하락 마감했다."));
    }

    @Test
    void outlookProfilesDoNotVetoOtherOutlooksOrUnrelatedEvents() {
        var first = article(1, "산업 비관론은 아직", "산업 변화가 기업의 사업에 미칠 영향에도 관심이 쏠린다.");
        var second = article(2, "수요 둔화 전망", "산업 수요 감소 가능성을 분석하고 있다.");
        assertUnrelated(first, second);
        assertUnrelated(first, article(2, "기업 신제품 출시", "기업은 새로운 제품을 출시했다."));
    }

    @Test
    void metadataOutlooksCannotVetoFullTextSessions() {
        var close = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        var outlook = new ClusterArticle(2, 1, "산업 비관론은 아직", "산업 변화가 사업에 미칠 영향에도 관심이 쏠린다.", null,
                FetchStatus.METADATA_ONLY, 2, "fixture", null, close.eventTime(), close.eventTime(), List.of(), null, null, null, true);
        assertUnrelated(close, outlook);
    }

    @Test
    void numericSnapshotNeedsAnActualCloseAnchorAndSameExplicitDate() {
        String values = "다우지수는 0.31% 내린 41234.50을 기록했다. 나스닥은 0.53% 하락한 23456.78을 기록했다.";
        var anchor = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 0.31% 내린 4만1234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.");
        var snapshot = article(2, "기술주 약세 확산", "뉴욕증시가 약세를 보였다. 17일 " + values);
        assertMatch(anchor, snapshot);
        assertTrue(evidence(snapshot, snapshot).marketReport(2));
        var otherSnapshot = article(3, "기술주 동반 약세", "17일 뉴욕증시는 약세였다. " + values);
        assertFalse(evidence(snapshot, otherSnapshot).matches(2, 3));
        var anotherDay = article(2, snapshot.title(), "16일 뉴욕증시는 약세였다. " + values);
        assertFalse(evidence(anchor, anotherDay).matches(1, 2));
        assertTrue(evidence(anchor, anotherDay).conflicts(1, 2));
    }

    @Test
    void sectorRoundupHasMarketScopeButCompanyCorporateNewsDoesNot() {
        var anchor = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        String body = "17일 뉴욕증시에서 주요 반도체 종목들이 급락했다. 필라델피아반도체지수는 5.12% 내린 12345.67에 거래를 마쳤다.";
        var sector = article(2, "AMD·인텔 주가 급락", body);
        assertMatch(anchor, sector);
        assertTrue(evidence(sector, sector).marketReport(2));
        assertUnrelated(anchor, article(2, "AMD·인텔 합병 발표", body));
        assertUnrelated(anchor, article(2, "[美증시 특징주] AMD 주가 상승", body));
        assertUnrelated(anchor, article(2, "기업 주가 상승", "기업은 인수를 완료했다. " + body));
    }

    @Test
    void structuredIndexSummaryCanFollowTheOpeningButRelatedArticlesCannotProvideEvidence() {
        var anchor = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        String opening = "17일 뉴욕증시는 기술주를 중심으로 약세였다.\n";
        String explanation = "시장의 투자 심리가 위축됐다.\n".repeat(55);
        String summary = "주요 지수 요약\n다우존스30: 0.31% 내린 4만1234.50에 마감했다.\n나스닥 종합: 0.53% 내린 23456.78에 마감했다.";
        assertMatch(anchor, article(2, "기술주 약세", opening + explanation + summary));
        assertUnrelated(anchor, article(2, "기술주 약세", opening + explanation + "\n관련 기사\n" + summary));
    }

    @Test
    void numericFallbackCannotPromoteOutlooksOtherMarketsOrMixedMarketRoundups() {
        var anchor = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 0.31% 내린 41234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.");
        String close = "17일 뉴욕증시에서 다우지수는 0.31% 내린 41234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.";
        for (var other : List.of(
                article(2, "기술주 상승 전망", close),
                article(2, "뉴욕증시·코스피 약세", close),
                article(2, "산업 수요 전망", "산업 변화가 기업의 사업에 미칠 영향에도 관심이 쏠린다.\n" + close),
                article(2, "기술주 약세", "17일 뉴욕증시는 약세였다. 코스피는 0.31% 내린 41234.50을 기록했다. 코스닥은 0.53% 내린 23456.78을 기록했다."))) {
            assertUnrelated(anchor, other);
            assertFalse(evidence(anchor, other).marketReport(2), other.body());
        }
    }

    @Test
    void companyResultsAndIndividualPriceMovesCannotUseMarketNumbersAsTheirScope() {
        var anchor = article(1, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        String background = "\n17일 뉴욕증시에서 다우지수는 0.31% 내린 41234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.";
        for (var other : List.of(
                article(2, "엔비디아 사상 최대 실적 발표", "엔비디아는 분기 실적을 발표했다." + background),
                article(2, "AMD 실적 부진에 급락", "AMD는 실적 부진으로 급락했다." + background),
                article(2, "엔비디아 10% 급등", "엔비디아의 주가는 크게 올랐다." + background),
                article(2, "[뉴욕증시] AMD 실적 발표", "AMD는 분기 실적을 발표했다." + background))) {
            assertUnrelated(anchor, other);
            assertFalse(evidence(anchor, other).marketReport(2));
        }
    }

    @Test
    void undatedMarketRoundupOnlyConflictsWithStrictIndustryOutlooks() {
        var roundup = article(1, "인텔·AMD 희비 엇갈린 증시…반도체주 하락",
                "간밤 뉴욕증시에서는 반도체와 인프라 관련주가 하락했다. 소프트웨어 업종은 반등했다.");
        var outlook = article(2, "메모리 비관론은 아직",
                "기술 개발 논쟁이 기업의 사업에 미칠 영향에도 관심이 쏠린다.");
        var pair = evidence(roundup, outlook);
        assertTrue(pair.conflicts(1, 2));
        assertTrue(pair.conflicts(2, 1));
        assertFalse(pair.matches(1, 2));
        assertFalse(pair.marketReport(1));
        var close = article(3, "뉴욕증시 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.");
        assertUnrelated(roundup, close);
    }

    @Test
    void roundupConflictCannotComeFromPredictionsCorporateEventsOrBackground() {
        var outlook = article(1, "메모리 비관론은 아직",
                "기술 개발 논쟁이 기업의 사업에 미칠 영향에도 관심이 쏠린다.");
        for (var other : List.of(
                article(2, "인텔·AMD 증시 희비", "뉴욕증시에서 반도체 관련주가 상승할 가능성이 제기됐다."),
                article(2, "인텔·AMD 증시 희비", "뉴욕증시에서 반도체 관련주가 상승할 것으로 예상했다."),
                article(2, "인텔·AMD 증시 희비", "뉴욕증시에서 반도체 관련주가 장중 상승했다."),
                article(2, "인텔·AMD 증시 희비", "과거 뉴욕증시에서 반도체 관련주가 상승했다."),
                article(2, "인텔·AMD 공급 계약 체결", "뉴욕증시에서 공급 계약을 체결한 반도체 관련주가 상승했다."),
                article(2, "인텔·AMD 증시 희비", "새로운 기술이 공개됐다. 뉴욕증시에서는 반도체 관련주가 하락했다."))) {
            assertUnrelated(outlook, other);
        }
    }

    @Test
    void primaryDatedMarketSnapshotNeedsAnExactSectorQuoteAndActualClose() {
        String quote = "필라델피아 반도체 지수는 전장보다 100.25포인트(2.35%) 하락한 4천200.50을 나타냈다.";
        var snapshot = article(1, "오늘 증시 이슈…코스피 외국인 동향",
                "뉴욕 증시가 17일(현지시간) 일제히 하락했습니다. " + quote);
        var close = article(2, "미국 증시 하락",
                "17일 뉴욕증시는 약세를 보였다. 필라델피아 반도체지수는 2.35% 내린 4200.50에 마감했다.");
        assertMatch(snapshot, close);
        assertTrue(evidence(snapshot, close).marketReport(1));
        var otherSnapshot = article(3, snapshot.title(), snapshot.body());
        assertFalse(evidence(snapshot, otherSnapshot).matches(1, 3));
        for (String mismatch : List.of("2.36% 내린 4200.50", "2.35% 내린 4200.51", "2.35% 오른 4200.50")) {
            assertUnrelated(snapshot, article(2, close.title(),
                    "17일 뉴욕증시에서 필라델피아 반도체지수는 " + mismatch + "에 마감했다."));
        }
        assertUnrelated(snapshot, article(2, close.title(), "17일 뉴욕증시에서 다우지수는 하락 마감했다."));
        var previous = article(2, close.title(), close.body().replace("17일", "16일"));
        assertFalse(evidence(snapshot, previous).matches(1, 2));
        assertTrue(evidence(snapshot, previous).conflicts(1, 2));
    }

    @Test
    void singleSectorQuoteCannotBorrowItsScopeFromBackgroundOrIntradayReports() {
        String quote = "필라델피아 반도체지수는 2.35% 하락한 4200.50을 기록했다.";
        var close = article(1, "뉴욕증시 하락",
                "17일 뉴욕증시에서 필라델피아 반도체지수는 2.35% 내린 4200.50에 마감했다.");
        for (String opening : List.of(
                "뉴욕증시가 일제히 하락했다. ",
                "뉴욕증시가 17일 장중 하락했다. ",
                "뉴욕증시가 17일 상승할 가능성이 제기됐다. ",
                "업계가 전망을 내놨다. 뉴욕증시가 17일 하락했다. ",
                "17일 뉴욕증시에서 사이버보안 관련주가 상승했다. ",
                "뉴욕증시가 지난달 17일 일제히 하락했다. ",
                "뉴욕증시가 17일 하락했다. 16일 뉴욕증시가 하락했다. ")) {
            assertUnrelated(close, article(2, "오늘 증시 이슈…코스피 외국인 동향", opening + quote));
        }
        for (String title : List.of("엔비디아 신제품 출시", "산업 수요 전망", "뉴욕증시와 코스피 하락")) {
            assertUnrelated(close, article(2, title, "뉴욕증시가 17일 하락했다. " + quote));
        }
        for (String observation : List.of("장중 ", "전날 ", "한때 ")) {
            assertUnrelated(close, article(2, "오늘 증시 이슈…코스피 외국인 동향",
                    "뉴욕증시가 17일 하락했다. " + observation + quote));
        }
    }

    private static void assertMatch(ClusterArticle first, ClusterArticle second) {
        var pair = evidence(first, second);
        assertTrue(pair.matches(first.articleId(), second.articleId()), second.body());
        assertTrue(pair.matches(second.articleId(), first.articleId()), second.body());
        assertFalse(pair.conflicts(first.articleId(), second.articleId()), second.body());
    }

    private static void assertUnrelated(ClusterArticle first, ClusterArticle second) {
        var pair = evidence(first, second);
        assertFalse(pair.matches(first.articleId(), second.articleId()), second.title() + " " + second.body());
        assertFalse(pair.conflicts(first.articleId(), second.articleId()), second.title() + " " + second.body());
        assertFalse(pair.conflicts(second.articleId(), first.articleId()), second.title() + " " + second.body());
    }

    private static MarketSessionEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new MarketSessionEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }

    private static ClusterArticle article(long id, String title, String body) {
        return article(id, title, body, "2026-06-18T09:00:00+09:00");
    }

    private static ClusterArticle article(long id, String title, String body, String reportedAt) {
        OffsetDateTime time = OffsetDateTime.parse(reportedAt);
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT,
                id, "fixture-" + id, null, time, time, List.of(), null, null, null, true);
    }
}
