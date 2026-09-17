package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArticleBodyCleanerNavigationTest {

    private static final String STORY = "삼성전자가 신규 반도체 공장 투자 계획을 발표했다.\n\n경제 상황을 고려한 결정이다.";

    @ParameterizedTest
    @ValueSource(strings = {"\n\n", "\n", "\r\n", " ", " | ", "\u00a0"})
    void removesReportedLeadingNewsMenu(String separator) {
        String menu = String.join(separator, "최신뉴스", "정치", "경제", "사회", "생활문화", "스포츠", "국제", "날씨");

        assertEquals(STORY, ArticleBodyCleaner.withoutLeadingNavigation(menu + separator + STORY));
    }

    @Test
    void removesWholeMenuLinesWithoutTheLatestNewsLabel() {
        assertEquals(STORY, ArticleBodyCleaner.withoutLeadingNavigation("정치\n경제\n사회\n" + STORY));
        assertEquals(STORY, ArticleBodyCleaner.withoutLeadingNavigation("정치 경제 사회\n" + STORY));
    }

    @Test
    void handlesSpacedMenuLabels() {
        assertEquals(STORY, ArticleBodyCleaner.withoutLeadingNavigation(
                "  최신 뉴스\n\n정치\n생활 · 문화\n날씨\n" + STORY));
        assertEquals(STORY, ArticleBodyCleaner.withoutLeadingNavigation(
                "  최신 뉴스 | 정치 | 생활 · 문화 | 날씨 " + STORY));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "경제\n\n경제 상황을 고려한 결정이다.",
            "정치 경제 사회 분야를 모두 취재했다.",
            "정치·경제·사회 분야의 변화를 분석했다.",
            "최신뉴스를 정치 경제 사회 순서로 소개했다.",
            "최신뉴스 정치 경제학의 변화를 살펴봤다.",
            "정치\n정치\n정치\n공약을 발표했다.",
            "생활문화\n생활·문화\n생활/문화\n행사를 개최했다.",
            "기사 첫 문장이다.\n최신뉴스\n정치\n경제\n사회\n기사 마지막 문장이다."
    })
    void preservesProseIsolatedHeadingsAndNonLeadingLabels(String body) {
        assertEquals(body, ArticleBodyCleaner.withoutLeadingNavigation(body));
    }

    @Test
    void doesNotRemoveAHeadingAfterTheMenuWhenItBeginsARealSentence() {
        String prose = "경제 전망은 밝다.\n\n후속 문장이다.";
        assertEquals(prose, ArticleBodyCleaner.withoutLeadingNavigation("정치\n사회\n국제\n" + prose));
        assertEquals(prose, ArticleBodyCleaner.withoutLeadingNavigation("최신뉴스\n정치\n사회\n국제\n" + prose));
        assertEquals(prose, ArticleBodyCleaner.withoutLeadingNavigation(
                "최신뉴스 정치 경제 사회 생활문화 스포츠 국제 날씨 " + prose));
    }

    @Test
    void handlesEmptyAndMenuOnlyContent() {
        assertEquals("", ArticleBodyCleaner.withoutLeadingNavigation(null));
        assertEquals("", ArticleBodyCleaner.withoutLeadingNavigation("  \n"));
        assertEquals("", ArticleBodyCleaner.withoutLeadingNavigation("최신뉴스\n정치\n경제"));
    }
}
