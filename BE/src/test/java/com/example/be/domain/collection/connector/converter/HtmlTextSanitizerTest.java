package com.example.be.domain.collection.connector.converter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HtmlTextSanitizerTest {

    @Test
    void removesTagsAndDecodesEntities() {
        assertEquals("삼성전자 Q4 & 실적", HtmlTextSanitizer.sanitize("<b>삼성전자</b> Q4 &amp; 실적"));
    }

    /**
     * 엔티티를 먼저 디코드하면 {@code &lt;b&gt;}가 진짜 태그로 변한 뒤 지워진다. 원문이 이스케이프해 둔 글자는 남아야 한다.
     */
    @Test
    void keepsEscapedMarkupAsText() {
        assertEquals("<b>양산</b>한다", HtmlTextSanitizer.sanitize("&lt;b&gt;양산&lt;/b&gt;한다"));
    }

    @Test
    void removesEveryTagNotOnlyTheFirst() {
        assertEquals("HBM4 양산", HtmlTextSanitizer.sanitize("<b>HBM4</b> <em>양산</em>"));
    }

    /**
     * 여는 꺾쇠 뒤에 영문자가 없으면 태그가 아니다. 이걸 안 보면 비교식이 통째로 사라진다.
     */
    @Test
    void keepsComparisonText() {
        assertEquals("A < B > C", HtmlTextSanitizer.sanitize("A < B > C"));
    }

    /**
     * 반도체 기사에 흔한 표기다. {@code <7nm ... >}를 태그로 보면 공정 이야기가 사라진다.
     */
    @Test
    void keepsAngleBracketsAroundNonLetters() {
        assertEquals("<7nm 공정에서 5nm로> 전환", HtmlTextSanitizer.sanitize("<7nm 공정에서 5nm로> 전환"));
    }

    @Test
    void trimsSurroundingWhitespaceLeftByTags() {
        assertEquals("HBM4", HtmlTextSanitizer.sanitize("<p> HBM4 </p>"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(HtmlTextSanitizer.sanitize(null));
    }

    /**
     * ★ #32 C1. 정규식이 못 끊던 자리다 — 속성값 안의 {@code >}에서 태그가 잘려
     * {@code y">링크}가 본문에 남았다. 파서는 속성 안이라는 걸 안다.
     */
    @Test
    void removesTagWhoseAttributeContainsAngleBracket() {
        assertEquals("링크", HtmlTextSanitizer.sanitize("<a title=\"x > y\">링크</a>"));
    }

    @Test
    void removesTagWhoseAttributeContainsAngleBracketInSingleQuotes() {
        assertEquals("HBM4 양산",
                HtmlTextSanitizer.sanitize("<span data-note='7nm -> 5nm'>HBM4</span> 양산"));
    }

    /**
     * 닫는 태그가 없는 마크업도 온다. 정규식은 열린 태그만 지우고 끝냈고, 파서는 문서로 복구한다.
     */
    @Test
    void handlesUnclosedTags() {
        assertEquals("삼성전자 실적", HtmlTextSanitizer.sanitize("<b>삼성전자 <i>실적"));
    }

    /**
     * 블록 태그로 나뉜 낱말이 눌어붙지 않는다. 정규식은 태그만 지워 "HBM4양산"이 됐다.
     */
    @Test
    void keepsWordsApartAcrossBlockTags() {
        assertEquals("HBM4 양산", HtmlTextSanitizer.sanitize("<p>HBM4</p><p>양산</p>"));
    }

    @Test
    void collapsesWhitespaceAndNewlines() {
        assertEquals("HBM4 양산", HtmlTextSanitizer.sanitize("HBM4  \n\t 양산"));
    }

    @Test
    void returnsEmptyStringForMarkupOnlyInput() {
        assertEquals("", HtmlTextSanitizer.sanitize("<br/><hr>"));
    }
}
