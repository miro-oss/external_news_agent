package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleContentExtractorTest {

    /** 본문 판정 임계값(200자)을 넘겨야 실제 기사처럼 취급된다. 페이월 안내문과 구분하는 기준이다. */
    private static final String PARAGRAPH =
            ("삼성전자가 HBM4 양산 일정을 앞당기기로 했다. 업계에 따르면 이번 결정은 고객사 요구를 반영한 것이다. ").repeat(3);

    private static final String PUBLISHER_NOTICE =
            "대표이사 : 합성 담당자 " + "테스트용 매체의 운영 안내입니다. ".repeat(8);
    private static final String COPYRIGHT_NOTICE =
            "무단 전재 및 재배포 금지. " + "테스트용 매체의 저작권 안내입니다. ".repeat(4);

    @Test
    void takesArticleBody() {
        String html = """
                <html><body>
                  <nav>메뉴 메뉴 메뉴</nav>
                  <article>
                    <p>%s</p>
                    <p>%s</p>
                  </article>
                  <footer>회사 소개</footer>
                </body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertTrue(body.contains("HBM4 양산 일정"));
        assertFalse(body.contains("메뉴"));
        assertFalse(body.contains("회사 소개"));
    }

    /**
     * 스크립트를 남기면 본문에 자바스크립트가 섞여 들어간다. M4의 문장 분할이 그대로 오염된다.
     */
    @Test
    void dropsScriptAndStyle() {
        String html = """
                <html><body><article>
                  <script>var tracking = "안 보여야 한다";</script>
                  <style>.a { color: red; }</style>
                  <p>%s</p><p>%s</p>
                </article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertFalse(body.contains("tracking"));
        assertFalse(body.contains("color"));
    }

    /**
     * 아는 자리에 없으면 문단이 가장 많이 모인 블록을 본문으로 본다.
     */
    @Test
    void fallsBackToDensestParagraphBlock() {
        String html = """
                <html><body>
                  <div class="side"><p>짧은 홍보</p></div>
                  <div class="unknown-wrapper"><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertTrue(body.contains("HBM4 양산 일정"));
        assertFalse(body.contains("짧은 홍보"));
    }

    @Test
    void rejectsPublisherFooterOnlyInDensestParagraphBlock() {
        String html = """
                <html><body><div><p>%s</p><p>%s</p></div></body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void skipsPublisherFooterForLaterKnownArticleSelector() {
        String html = """
                <html><body>
                  <article><p>%s</p><p>%s</p></article>
                  <div class="article-body"><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE, PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip()), body);
    }

    @Test
    void selectsUsableArticleInsteadOfLongerPublisherFooterBlock() {
        String html = """
                <html><body>
                  <div><p>%s</p><p>%s</p></div>
                  <div><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PUBLISHER_NOTICE.repeat(3), COPYRIGHT_NOTICE.repeat(3), PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip()), body);
    }

    @Test
    void rejectsShortArticlePaddedByPublisherFooter() {
        String html = """
                <html><body><article><p>합성 연구소가 새 실험 일정을 발표했다.</p><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void preservesOriginalArticleTextWithPublisherFooter() {
        String html = """
                <html><body><article><p>%s</p><p>%s</p><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH, PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip(),
                PUBLISHER_NOTICE.strip(), COPYRIGHT_NOTICE.strip()), body);
    }

    /**
     * 페이월은 보통 로그인 안내 한 줄만 준다. 그걸 본문으로 저장하면 분석이 쓰레기를 읽는다.
     */
    @Test
    void returnsNullForPaywallStub() {
        String html = "<html><body><article><p>로그인 후 이용해 주세요.</p></article></body></html>";

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void returnsNullForEmptyInput() {
        assertNull(ArticleContentExtractor.extract((String) null, "https://example.com/1"));
        assertNull(ArticleContentExtractor.extract((byte[]) null, "https://example.com/1"));
        assertNull(ArticleContentExtractor.extract("   ", "https://example.com/1"));
    }

    /**
     * 문단을 한 줄로 붙이면 M4의 문장 분할이 어려워진다.
     */
    @Test
    void keepsParagraphBoundaries() {
        String html = """
                <html><body><article><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        assertTrue(ArticleContentExtractor.extract(html, "https://example.com/1").contains("\n\n"));
    }
}
