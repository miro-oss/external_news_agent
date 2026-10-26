package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedParserTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>한국경제 경제</title>
                <link>https://www.hankyung.com/economy</link>
                <item>
                  <title>&lt;b&gt;삼성전자&lt;/b&gt; HBM4 양산 &amp;amp; 공급</title>
                  <link>https://www.hankyung.com/article/2026081200001</link>
                  <description>삼성전자가 HBM4를 양산한다</description>
                  <pubDate>Mon, 10 Aug 2026 09:00:00 +0900</pubDate>
                  <guid>https://www.hankyung.com/article/2026081200001</guid>
                </item>
                <item>
                  <title>링크 없는 기사</title>
                  <description>저장할 URL이 없다</description>
                  <pubDate>Mon, 10 Aug 2026 10:00:00 +0900</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private static final String ATOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>EE Times</title>
              <entry>
                <title>SK hynix ships HBM4</title>
                <link rel="edit" href="https://www.eetimes.com/edit/1"/>
                <link rel="alternate" href="https://www.eetimes.com/sk-hynix-ships-hbm4/"/>
                <summary>SK hynix started shipping HBM4 samples</summary>
                <published>2026-08-10T09:00:00Z</published>
                <updated>2026-08-11T09:00:00Z</updated>
              </entry>
            </feed>
            """;

    @Test
    void parsesRssItems() {
        List<CollectedArticle> articles = parse(RSS, "ko");

        assertEquals(1, articles.size());
        CollectedArticle article = articles.get(0);
        assertEquals("https://www.hankyung.com/article/2026081200001", article.canonicalUrl());
        assertEquals("삼성전자가 HBM4를 양산한다", article.summary());
        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9)), article.publishedAt());
        assertEquals("www.hankyung.com", article.sourceName());
        assertEquals("ko", article.language());
    }

    /**
     * XML 파서가 먼저 한 겹을 푼다. 원문의 {@code &lt;b&gt;}는 파서를 지나면 진짜 {@code <b>} 태그가 되고,
     * {@code &amp;amp;}는 {@code &amp;}가 된다. sanitizer는 그 결과를 받아 태그를 지우고 남은 엔티티를 푼다.
     * 즉 XML 피드에서는 디코드가 두 번 일어난다.
     */
    @Test
    void sanitizesTitleMarkup() {
        assertEquals("삼성전자 HBM4 양산 & 공급", parse(RSS, "ko").get(0).title());
    }

    @Test
    void skipsItemsWithoutLink() {
        assertTrue(parse(RSS, "ko").stream()
                .noneMatch(article -> "링크 없는 기사".equals(article.title())));
    }

    /**
     * "RSS 주소"로 알려진 URL이 실제로는 Atom인 경우가 흔하다. 둘 다 받아야 한다.
     */
    @Test
    void parsesAtomEntries() {
        List<CollectedArticle> articles = parse(ATOM, "en");

        assertEquals(1, articles.size());
        assertEquals("SK hynix ships HBM4", articles.get(0).title());
        assertEquals("SK hynix started shipping HBM4 samples", articles.get(0).summary());
        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.UTC), articles.get(0).publishedAt());
    }

    /**
     * Atom은 link가 여러 개다. rel="edit" 같은 관리용 링크를 원문으로 저장하면 안 된다.
     */
    @Test
    void picksAlternateLinkFromAtom() {
        assertEquals("https://www.eetimes.com/sk-hynix-ships-hbm4/",
                parse(ATOM, "en").get(0).canonicalUrl());
    }

    /**
     * 시트 URL이 HTML 섹션 페이지인 경우가 있었다(#15). 파서가 터지면 실행 전체가 죽는다.
     */
    @Test
    void rejectsHtmlPage() {
        assertThrows(FeedParseException.class,
                () -> parse("<!DOCTYPE html><html><body><h1>News</h1></body></html>", "ko"));
    }

    /**
     * "기사가 0건인 피드"와 "읽지 못한 피드"는 다른 사건이다. 뒤엣것만 예외로 알린다.
     */
    @Test
    void rejectsBlankOrBrokenXml() {
        assertThrows(FeedParseException.class, () -> parse(null, "ko"));
        assertThrows(FeedParseException.class, () -> parse("   ", "ko"));
        assertThrows(FeedParseException.class, () -> parse("<rss><channel><item>", "ko"));
    }

    @Test
    void returnsEmptyForFeedWithoutItems() {
        assertTrue(parse("<rss version=\"2.0\"><channel><title>빈 피드</title></channel></rss>", "ko")
                .isEmpty());
    }

    /**
     * Atom 피드가 접두사를 붙여 오는 경우가 있다. 지역명으로 찾지 않으면 기사가 0건이 된다.
     */
    @Test
    void parsesPrefixedAtomEntries() {
        String prefixed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
                  <atom:entry>
                    <atom:title>Prefixed HBM4</atom:title>
                    <atom:link rel="alternate" href="https://www.eetimes.com/prefixed/"/>
                    <atom:summary>요약</atom:summary>
                    <atom:published>2026-08-10T09:00:00Z</atom:published>
                  </atom:entry>
                </atom:feed>
                """;

        List<CollectedArticle> articles = parse(prefixed, "en");

        assertEquals(1, articles.size());
        assertEquals("Prefixed HBM4", articles.get(0).title());
        assertEquals("https://www.eetimes.com/prefixed/", articles.get(0).canonicalUrl());
    }

    /**
     * 피드는 우리가 통제하지 않는 서버가 만든다. 외부 엔티티를 처리하면 로컬 파일이 새어 나간다.
     */
    @Test
    void refusesExternalEntities() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item>
                  <title>&xxe;</title>
                  <link>https://example.com/1</link>
                </item></channel></rss>
                """;

        assertThrows(FeedParseException.class, () -> parse(xxe, "ko"));
    }

    /**
     * 외부 엔티티만 막으면 내부 엔티티 중첩(billion laughs)으로 메모리가 터진다. DTD 자체를 거부해야 한다.
     */
    @Test
    void refusesEntityExpansionBomb() {
        String bomb = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [
                  <!ENTITY a "aaaaaaaaaa">
                  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                  <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                ]>
                <rss version="2.0"><channel><item>
                  <title>&c;</title>
                  <link>https://example.com/1</link>
                </item></channel></rss>
                """;

        assertThrows(FeedParseException.class, () -> parse(bomb, "ko"));
    }

    @Test
    void leavesPublishedAtEmptyWhenUnreadable() {
        String feed = """
                <rss version="2.0"><channel><item>
                  <title>발행일이 깨진 기사</title>
                  <link>https://example.com/1</link>
                  <pubDate>2026년 8월 10일</pubDate>
                </item></channel></rss>
                """;

        assertNull(parse(feed, "ko").get(0).publishedAt());
    }

    /**
     * link 요소가 쓸모없고 guid에만 URL이 있는 피드가 있다.
     */
    @Test
    void fallsBackToGuidWhenLinkIsEmpty() {
        String feed = """
                <rss version="2.0"><channel><item>
                  <title>guid만 있는 기사</title>
                  <link></link>
                  <guid>https://example.com/from-guid</guid>
                </item></channel></rss>
                """;

        assertEquals("https://example.com/from-guid", parse(feed, "ko").get(0).canonicalUrl());
    }

    /**
     * ★ #32 C2. 파서가 바이트를 받는 이유다 — 진짜 인코딩은 응답 헤더가 아니라 XML 선언에 있다.
     * 문자열로 미리 디코드하면 charset을 잘못 짚은 순간 한글이 깨진다(#29에서 기사 본문으로 겪었다).
     */
    @Test
    void readsEncodingFromXmlDeclarationNotFromCaller() {
        String feed = """
                <?xml version="1.0" encoding="EUC-KR"?>
                <rss version="2.0"><channel><item>
                  <title>삼성전자 HBM4 양산</title>
                  <link>https://example.com/1</link>
                </item></channel></rss>
                """;

        List<CollectedArticle> articles = FeedParser.parse(feed.getBytes(Charset.forName("EUC-KR")), "ko");

        assertEquals("삼성전자 HBM4 양산", articles.get(0).title());
    }

    @Test
    void rejectsEmptyBody() {
        assertThrows(FeedParseException.class, () -> FeedParser.parse((byte[]) null, "ko"));
        assertThrows(FeedParseException.class, () -> FeedParser.parse(new byte[0], "ko"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,example", "file:///tmp/example.xml",
            "http://localhost/article", "http://127.0.0.1/article", "http://10.0.0.1/article",
            "http://169.254.169.254/metadata", "https://metadata.google.internal/article",
            "http://[::1]/article", "https://user:pass@news.example/article",
            "https://news.example:0/article", "https://news.example:65536/article",
            "/relative-article", "//news.example/article"})
    void skipsUnsafeRssAtomAndGuidLinksWhileKeepingValidArticles(String url) {
        List<String> feeds = List.of(
                "<rss><channel><item><title>unsafe</title><link>" + url + "</link></item>"
                        + "<item><title>valid</title><link>https://news.example/valid</link></item></channel></rss>",
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>unsafe</title><link href=\"" + url + "\"/></entry>"
                        + "<entry><title>valid</title><link href=\"https://news.example/valid\"/></entry></feed>",
                "<rss><channel><item><title>unsafe</title><guid>" + url + "</guid></item>"
                        + "<item><title>valid</title><link>https://news.example/valid</link></item></channel></rss>");

        for (String feed : feeds) {
            List<CollectedArticle> articles = parse(feed, "ko");
            assertEquals(1, articles.size());
            assertEquals("https://news.example/valid", articles.get(0).canonicalUrl());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://news.example/article", "https://news.example/article"})
    void preservesPublicLinksAndTreatsHtmlTitlesAsPlainText(String url) {
        String feed = "<rss><channel><item><title><![CDATA[<img src=x onerror=alert(1)>ordinary title]]></title>"
                + "<link>" + url + "</link><description><![CDATA[<script>alert(1)</script>summary]]></description>"
                + "</item></channel></rss>";

        CollectedArticle article = parse(feed, "ko").get(0);

        assertEquals(url, article.canonicalUrl());
        assertEquals("ordinary title", article.title());
        assertEquals("summary", article.summary());
    }

    @ParameterizedTest
    @MethodSource("invalidArticleMetadata")
    void skipsOnlyInvalidRssAndAtomMetadataBeforeItCanFailTheStorageBatch(String title, String url) {
        String titleXml = title == null ? "" : "<title><![CDATA[" + title + "]]></title>";
        List<String> feeds = List.of(
                "<rss><channel><item>" + titleXml + "<link>" + url + "</link></item>"
                        + "<item><title>valid</title><link>https://news.example/valid</link></item></channel></rss>",
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>" + titleXml + "<link href=\"" + url + "\"/></entry>"
                        + "<entry><title>valid</title><link href=\"https://news.example/valid\"/></entry></feed>");

        for (String feed : feeds) {
            List<CollectedArticle> articles = parse(feed, "ko");
            assertEquals(1, articles.size());
            assertEquals("valid", articles.get(0).title());
            assertEquals("https://news.example/valid", articles.get(0).canonicalUrl());
        }
    }

    private static java.util.stream.Stream<Arguments> invalidArticleMetadata() {
        String longHost = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(9);
        String prefix = "https://news.example/";
        return java.util.stream.Stream.of(
                Arguments.of("x".repeat(1001), prefix + "article"),
                Arguments.of(null, prefix + "article"),
                Arguments.of("", prefix + "article"),
                Arguments.of("   ", prefix + "article"),
                Arguments.of("<script>alert(1)</script>", prefix + "article"),
                Arguments.of("ordinary title", prefix + "x".repeat(2001 - prefix.length())),
                Arguments.of("ordinary title", "https://" + longHost + "/article"));
    }

    @Test
    void acceptsMetadataExactlyAtTheStorageLimits() {
        String host = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(8);
        String prefix = "https://" + host + "/";
        String url = prefix + "x".repeat(2000 - prefix.length());
        String title = "x".repeat(1000);
        String feed = "<rss><channel><item><title>" + title + "</title><link>" + url
                + "</link></item></channel></rss>";

        CollectedArticle article = parse(feed, "ko").get(0);

        assertEquals(title, article.title());
        assertEquals(url, article.canonicalUrl());
        assertEquals(host, article.sourceName());
    }

    @Test
    void acceptsMultibyteMetadataAtTheUtf8ByteLimits() {
        String title = "가".repeat(333) + "a";
        String prefix = "https://news.example/";
        int remaining = 2000 - prefix.length();
        String url = prefix + "가".repeat(remaining / 3) + "a".repeat(remaining % 3);
        String feed = "<rss><channel><item><title>" + title + "</title><link>" + url
                + "</link></item></channel></rss>";

        CollectedArticle article = parse(feed, "ko").get(0);

        assertEquals(1000, title.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(2000, url.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(title, article.title());
        assertEquals(url, article.canonicalUrl());
    }

    @Test
    void skipsMultibyteMetadataBeyondUtf8LimitsEvenWhenCharacterCountsFit() {
        String prefix = "https://news.example/";
        List<String[]> metadata = List.of(
                new String[]{"가".repeat(334), prefix + "article"},
                new String[]{"😀".repeat(251), prefix + "article"},
                new String[]{"ordinary title", prefix + "😀".repeat((2000 - prefix.length()) / 4 + 1)});

        for (String[] item : metadata) {
            assertTrue(item[0].length() <= 1000);
            assertTrue(item[1].length() <= 2000);
            String feed = "<rss><channel><item><title>" + item[0] + "</title><link>" + item[1] + "</link></item>"
                    + "<item><title>valid</title><link>https://news.example/valid</link></item></channel></rss>";
            List<CollectedArticle> articles = parse(feed, "ko");
            assertEquals(1, articles.size());
            assertEquals("valid", articles.get(0).title());
        }
    }

    private List<CollectedArticle> parse(String xml, String fallbackLanguage) {
        return FeedParser.parse(xml == null ? null : xml.getBytes(StandardCharsets.UTF_8), fallbackLanguage);
    }
}
