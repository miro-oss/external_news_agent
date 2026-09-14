package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstitutionalEventEvidenceTest {
    @Test
    void sameNamedTrainingPeriodSurvivesDifferentPublicationDays() {
        var first = training(1, "해솔대", "하계 반도체 공정 실습", "7월 16일부터 8월 3일까지", "2026-09-12T09:00:00+09:00");
        var second = training(2, "해솔대학교", "하계 반도체 공정 실습", "7월 16일~8월 3일", "2026-09-13T10:00:00+09:00");
        assertMatch(first, second);
    }

    @Test
    void monthCanBeOmittedOnlyAtEndOfAnExplicitRange() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일부터 20일까지");
        assertMatch(first, training(2, "해솔대", "반도체 장비 교육", "2026년 7월 16일~7월 20일"));
        assertNoMatch(first, training(2, "해솔대", "반도체 장비 교육", "16일부터 20일까지"));
    }

    @Test
    void identicalProgramWithDifferentPeriodOrInstitutionConflicts() {
        var first = training(1, "해솔대", "하계 반도체 공정 실습", "7월 16일~8월 3일");
        assertConflict(first, training(2, "해솔대", "하계 반도체 공정 실습", "7월 23일~8월 10일"));
        assertConflict(first, training(2, "누리대", "하계 반도체 공정 실습", "7월 16일~8월 3일"));
        assertConflict(first, training(2, "해솔대", "하계 반도체 공정 실습", "2025년 7월 16일~8월 3일"));
    }

    @Test
    void explicitDifferentEditionsConflict() {
        var first = article(1, "해솔대 제3기 '반도체 장비 교육' 성료",
                "해솔대는 7월 16일~8월 3일 제3기 '반도체 장비 교육'을 운영했다.");
        var second = article(2, "해솔대 제4기 '반도체 장비 교육' 성료",
                "해솔대는 7월 16일~8월 3일 제4기 '반도체 장비 교육'을 운영했다.");
        assertConflict(first, second);
        assertConflict(article(1, "해솔대 '제3기 반도체 장비 교육' 성료",
                        "해솔대는 7월 16일~8월 3일 '제3기 반도체 장비 교육'을 운영했다."),
                article(2, "해솔대 '제4기 반도체 장비 교육' 성료",
                        "해솔대는 7월 16일~8월 3일 '제4기 반도체 장비 교육'을 운영했다."));
    }

    @Test
    void missingPeriodIsUnknownAndSamePublicationDateCannotReplaceIt() {
        var dated = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        var undated = training(2, "해솔대", "반도체 장비 교육", "여름방학 중");
        assertNoMatch(dated, undated);
        assertFalse(evidence(dated, undated).conflicts(1, 2));
        assertNoMatch(undated, training(1, "해솔대", "반도체 장비 교육", "여름방학 중"));
    }

    @Test
    void genericTrainingNameCannotSupplyIdentity() {
        assertNoMatch(training(1, "해솔대", "하계 교육", "7월 16일~8월 3일"),
                training(2, "해솔대", "하계 교육", "7월 16일~8월 3일"));
    }

    @Test
    void differentNamedProgramsOnTheSameDatesStaySeparate() {
        assertNoMatch(training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일"),
                training(2, "해솔대", "인공지능 설계 교육", "7월 16일~8월 3일"));
    }

    @Test
    void unquotedDistinctiveProgramStillRequiresItsExactLeadNameAndPeriod() {
        var first = article(1, "해솔대, 반도체 공정 실습 성료",
                "해솔대는 7월 16일~8월 3일 반도체 공정 실습을 운영했다.");
        var second = article(2, "해솔대학교, 반도체 공정 실습 마무리",
                "해솔대학교는 7월 16일부터 8월 3일까지 반도체 공정 실습을 실시했다.");
        assertMatch(first, second);
    }

    @Test
    void explicitDatedForumIsNotAnAnnouncementDate() {
        var first = article(1, "해솔연구원 '미래 에너지 포럼' 개최",
                "해솔연구원은 9월 7일 '미래 에너지 포럼'을 개최했다.");
        assertMatch(first, article(2, first.title(), first.body()));
        assertConflict(first, article(2, first.title(), first.body().replace("9월 7일", "9월 8일")));
        var reportDate = article(2, first.title(),
                "해솔연구원은 9월 7일 '미래 에너지 포럼'을 개최했다고 밝혔다.");
        assertNoMatch(first, reportDate);
        assertFalse(evidence(first, reportDate).conflicts(1, 2));
    }

    @Test
    void periodWinsOverASeparateExplicitReportingDayInTheSameSentence() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        var second = article(2, "해솔대 '반도체 장비 교육' 성료",
                "해솔대는 7월 16일부터 8월 3일까지 '반도체 장비 교육'을 운영했다고 9월 13일 밝혔다.");
        assertMatch(first, second);
    }

    @Test
    void unrelatedRecruitmentPeriodCannotBecomeTheTrainingPeriod() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        var second = article(2, first.title(),
                "해솔대는 7월 16일~8월 3일 채용 신청을 접수했고 '반도체 장비 교육'을 운영했다.");
        assertNoMatch(first, second);
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void impossibleDatesRelativeYearsAndMultiplePeriodsRemainUnknown() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        for (String ambiguous : List.of("2월 30일~3월 3일", "12월 16일~1월 3일",
                "지난해 7월 16일~8월 3일", "7월 16일~8월 3일 및 8월 16일~8월 23일")) {
            var second = training(2, "해솔대", "반도체 장비 교육", ambiguous);
            assertNoMatch(first, second);
            assertFalse(evidence(first, second).conflicts(1, 2));
        }
    }

    @Test
    void backgroundAndUnrelatedTitleCannotCreateAnOccasion() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        assertNoMatch(first, article(2, "해솔대 반도체 교육 투자 확대", first.body()));
        assertNoMatch(first, article(2, first.title(), "해솔대 교육 시설을 살펴본다.\n한편 " + first.body()));
        assertNoMatch(first, article(2, first.title(), "해솔대는 교육 방침을 설명했다. "
                + "학생들의 발전 가능성을 논의했다. ".repeat(50) + first.body()));
    }

    @Test
    void bodyAndSummaryAreNotMixedIntoTwoSeparateEvents() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        var second = article(2, first.title(), first.body(), "해솔대는 '반도체 장비 교육' 계획을 설명했다.",
                "2026-09-13T12:00:00+09:00");
        assertNoMatch(first, second);
        var metadataOnly = article(2, first.title(), first.body(), null, "2026-09-13T12:00:00+09:00");
        assertMatch(first, metadataOnly);
    }

    @Test
    void multipleProgramsAndDifferentHeadlineInstitutionRemainUncertain() {
        var first = training(1, "해솔대", "반도체 장비 교육", "7월 16일~8월 3일");
        assertNoMatch(first, article(2, first.title(), first.body()
                + " '인공지능 설계 교육'도 7월 16일~8월 3일 운영했다."));
        assertNoMatch(first, article(2, first.title().replace("해솔대", "누리대"), first.body()));
    }

    @Test
    void admissionRequiresInstitutionAcademicYearPhaseAndExplicitResult() {
        var first = admissions(1, "해솔대", "2027학년도", "수시", "19.70");
        assertMatch(first, admissions(2, "해솔대학교", "2027학년도", "수시", "19.7"));
        assertNoMatch(first, admissions(2, "해솔대", "", "수시", "19.7"));
        var missingRate = article(2, "해솔대 수시 경쟁률 발표", "해솔대는 2027학년도 수시 모집 결과를 발표했다.");
        assertNoMatch(first, missingRate);
        assertFalse(evidence(first, missingRate).conflicts(1, 2));
    }

    @Test
    void admissionDifferentUniversityYearPhaseOrRateConflicts() {
        var first = admissions(1, "해솔대", "2027학년도", "수시", "19.7");
        assertConflict(first, admissions(2, "누리대", "2027학년도", "수시", "19.7"));
        assertConflict(first, admissions(2, "해솔대", "2026학년도", "수시", "19.7"));
        assertConflict(first, admissions(2, "해솔대", "2027학년도", "정시", "19.7"));
        assertConflict(first, admissions(2, "해솔대", "2027학년도", "수시", "22.3"));
    }

    @Test
    void admissionRatesCannotBeTakenFromDepartmentOrHistoricalBackground() {
        var first = admissions(1, "해솔대", "2027학년도", "수시", "19.7");
        assertNoMatch(first, article(2, "해솔대 수시 경쟁률 발표",
                "해솔대의 2027학년도 수시 모집 결과다. 컴퓨터학과는 19.7대 1을 기록했다."));
        assertNoMatch(first, article(2, "해솔대 수시 경쟁률 발표",
                "해솔대의 2027학년도 수시 모집 결과다.\n한편 지난해 평균 경쟁률은 19.7대 1이었다."));
    }

    @Test
    void anExplicitDepartmentOrHistoricalCompetitionRateIsNotAnOverallRate() {
        var first = admissions(1, "해솔대", "2027학년도", "수시", "19.7");
        for (String scoped : List.of(
                "컴퓨터학과 경쟁률은 31대 1이었다.",
                "컴퓨터학과의 평균 경쟁률은 31대 1이었다.",
                "학생부종합 전형의 전체 경쟁률은 31대 1이었다.",
                "의예과 경쟁률은 31대 1이었다.",
                "지난해 평균 경쟁률은 31대 1이었다.",
                "전년도 경쟁률은 31대 1이었다.")) {
            var second = article(2, "해솔대 2027학년도 수시 경쟁률 발표",
                    "해솔대의 2027학년도 수시 모집 결과다. " + scoped);
            assertNoMatch(first, second);
            assertNoMatch(admissions(1, "해솔대", "2027학년도", "수시", "31"), second);
            assertFalse(evidence(first, second).conflicts(1, 2));
        }
    }

    @Test
    void aScopedHeadlineRateCanNeitherMatchNorVetoTheCurrentOverallResult() {
        for (String headline : List.of(
                "해솔대 2027학년도 수시 컴퓨터학과 경쟁률 31대 1",
                "해솔대 2027학년도 수시 학생부종합 전형 경쟁률 31대 1",
                "해솔대 2027학년도 수시 경쟁률 하락…지난해 31대 1")) {
            var second = article(2, headline,
                    "해솔대는 2027학년도 수시 모집을 마감했다. 전체 경쟁률은 19.7대 1이었다.");
            for (String rate : List.of("19.7", "31")) {
                var first = admissions(1, "해솔대", "2027학년도", "수시", rate);
                assertNoMatch(first, second);
                assertFalse(evidence(first, second).conflicts(1, 2));
            }
        }
    }

    @Test
    void aSeparateDepartmentSentenceDoesNotReplaceTheKnownOverallRate() {
        var first = admissions(1, "해솔대", "2027학년도", "수시", "19.7");
        var second = article(2, "해솔대 2027학년도 수시 경쟁률 발표",
                "해솔대는 2027학년도 수시 모집을 마감했다. 전체 경쟁률은 19.7대 1이었다. "
                        + "컴퓨터학과 경쟁률은 31대 1이었다.");
        assertMatch(first, second);
    }

    @Test
    void explicitUniversityAliasUsesTheSharedSubjectCanonicalization() {
        assertMatch(admissions(1, "한국기술교육대학교", "2027학년도", "수시", "19.7"),
                admissions(2, "한기대", "2027학년도", "수시", "19.7"));
    }

    @Test
    void admissionsHeadlineCountsMayIdentifyTheSameExplicitOverallRate() {
        var first = admissions(1, "한국에너지공과대학교", "2028학년도", "수시", "31");
        var counted = article(2, "켄텍 수시 전형, 100명 모집에 3,100명 몰려",
                "켄텍은 2028학년도 수시모집에 100명 모집, 3100명 지원으로 경쟁률 31대 1을 기록했다.");
        assertMatch(first, counted);
        assertNoMatch(first, article(2, counted.title().replace("3,100명", "2,100명"), counted.body()));
        assertNoMatch(first, article(2, counted.title(), counted.body().replace("경쟁률 31대 1을 기록했다", "모집을 마감했다")));
        assertNoMatch(first, article(2, counted.title(),
                "켄텍은 2028학년도 수시모집을 마감했다. 컴퓨터학과는 31대 1을 기록했다."));
        assertNoMatch(first, article(2, counted.title(),
                "켄텍은 2028학년도 수시모집을 마감했다.\n한편 지난해 평균 경쟁률은 31대 1이었다."));
    }

    @Test
    void leadingPhotoCaptionCannotOverrideTheCurrentInstitutionAndResult() {
        var first = admissions(1, "한국기술교육대학교", "2027학년도", "수시", "19.7");
        var alias = admissions(2, "한기대", "2027학년도", "수시", "19.7");
        assertMatch(first, article(2, alias.title(), "다른대학 전경 [사진=자료사진]\n" + alias.body()));
    }

    @Test
    void absentProfileNeverMatchesOrConflicts() {
        var first = article(1, "해솔대 교육 현장", null);
        var second = article(2, "해솔대 교육 현장", null);
        assertNoMatch(first, second);
        assertFalse(evidence(first, second).conflicts(1, 2));
        assertFalse(evidence(first, second).matches(1, 99));
        assertFalse(evidence(first, second).conflicts(1, 99));
    }

    private static ClusterArticle training(long id, String institution, String name, String period) {
        return training(id, institution, name, period, "2026-09-12T12:00:00+09:00");
    }

    private static ClusterArticle training(long id, String institution, String name, String period, String publication) {
        return article(id, institution + " '" + name + "' 성료", null,
                institution + "는 " + period + " '" + name + "'을 운영했다.", publication);
    }

    private static ClusterArticle admissions(long id, String institution, String year, String phase, String rate) {
        return article(id, institution + " " + year + " " + phase + " 경쟁률 " + rate + "대 1",
                institution + "는 " + year + " " + phase + " 모집을 마감했다. 평균 경쟁률은 " + rate + "대 1이다.");
    }

    private static ClusterArticle article(long id, String title, String body) {
        return article(id, title, null, body, "2026-09-12T12:00:00+09:00");
    }

    private static ClusterArticle article(long id, String title, String summary, String body, String publication) {
        OffsetDateTime time = OffsetDateTime.parse(publication);
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }

    private static InstitutionalEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new InstitutionalEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }

    private static void assertMatch(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(first, second);
        assertTrue(evidence.matches(1, 2));
        assertTrue(evidence.matches(2, 1));
        assertFalse(evidence.conflicts(1, 2));
    }

    private static void assertNoMatch(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(first, second);
        assertFalse(evidence.matches(1, 2));
        assertFalse(evidence.matches(2, 1));
    }

    private static void assertConflict(ClusterArticle first, ClusterArticle second) {
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
        assertTrue(evidence(first, second).conflicts(2, 1));
    }
}
