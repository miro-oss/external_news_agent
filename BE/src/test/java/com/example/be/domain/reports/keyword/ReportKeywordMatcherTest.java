package com.example.be.domain.reports.keyword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportKeywordMatcherTest {
    @Test void normalizesWidthCaseAndUnicodeWhitespaceWithoutGuessingSynonyms() {
        assertEquals("ai 반도체", ReportKeywordMatcher.key("  ＡＩ\u00a0\t반도체  "));
        assertEquals("AI 반도체", ReportKeywordMatcher.label(" ＡＩ\u3000반도체 "));
        assertTrue(ReportKeywordMatcher.matches("ai 반도체", "ＡＩ\n반도체 투자", ""));
        assertFalse(ReportKeywordMatcher.matches("ai", "인공지능 투자", ""));
    }

    @Test void latinAndDigitBoundariesAvoidIncidentalSubstrings() {
        assertFalse(ReportKeywordMatcher.matches("ai", "RAIL 투자를 발표", ""));
        assertFalse(ReportKeywordMatcher.matches("gpu", "GPU2 발표", ""));
        assertFalse(ReportKeywordMatcher.matches("ai", "éAI 발표", ""));
        assertTrue(ReportKeywordMatcher.matches("ai", "RAIL 관련 뉴스, AI 전망", ""));
        assertTrue(ReportKeywordMatcher.matches("gpu", "차세대 GPU·메모리", ""));
    }

    @Test void koreanSubstringAndLiteralPunctuationAreSupported() {
        assertTrue(ReportKeywordMatcher.matches("반도체", "반도체산업 전망", ""));
        assertTrue(ReportKeywordMatcher.matches("ai", "생성AI를 활용", ""));
        assertTrue(ReportKeywordMatcher.matches("c++", "C++ 도입", ""));
        assertFalse(ReportKeywordMatcher.matches("c++", "CCC 도입", ""));
    }

    @Test void matchesTitleOrBodyWithoutInventingAPhraseAcrossTheirBoundary() {
        assertTrue(ReportKeywordMatcher.matches("hbm", "오늘 발표", "HBM 공급 확대"));
        assertFalse(ReportKeywordMatcher.matches("ai 반도체", "AI", "반도체"));
        assertFalse(ReportKeywordMatcher.matches("", "내용", "내용"));
        assertFalse(ReportKeywordMatcher.matches("ai", null, null));
    }
}
