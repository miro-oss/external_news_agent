package com.example.be.domain.collection.connector;

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

    @Test
    void trimsSurroundingWhitespaceLeftByTags() {
        assertEquals("HBM4", HtmlTextSanitizer.sanitize("<p> HBM4 </p>"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(HtmlTextSanitizer.sanitize(null));
    }
}
