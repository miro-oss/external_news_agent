package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        List<CollectedArticle> articles = FeedParser.parse(RSS, "ko");

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
        assertEquals("삼성전자 HBM4 양산 & 공급", FeedParser.parse(RSS, "ko").get(0).title());
    }

    @Test
    void skipsItemsWithoutLink() {
        assertTrue(FeedParser.parse(RSS, "ko").stream()
                .noneMatch(article -> "링크 없는 기사".equals(article.title())));
    }

    /**
     * "RSS 주소"로 알려진 URL이 실제로는 Atom인 경우가 흔하다. 둘 다 받아야 한다.
     */
    @Test
    void parsesAtomEntries() {
        List<CollectedArticle> articles = FeedParser.parse(ATOM, "en");

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
                FeedParser.parse(ATOM, "en").get(0).canonicalUrl());
    }

    /**
     * 시트 URL이 HTML 섹션 페이지인 경우가 있었다(#15). 파서가 터지면 실행 전체가 죽는다.
     */
    @Test
    void returnsEmptyForHtmlPage() {
        assertTrue(FeedParser.parse("<!DOCTYPE html><html><body><h1>News</h1></body></html>", "ko").isEmpty());
    }

    @Test
    void returnsEmptyForBlankOrBrokenXml() {
        assertTrue(FeedParser.parse(null, "ko").isEmpty());
        assertTrue(FeedParser.parse("   ", "ko").isEmpty());
        assertTrue(FeedParser.parse("<rss><channel><item>", "ko").isEmpty());
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

        assertTrue(FeedParser.parse(xxe, "ko").isEmpty());
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

        assertTrue(FeedParser.parse(bomb, "ko").isEmpty());
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

        assertNull(FeedParser.parse(feed, "ko").get(0).publishedAt());
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

        assertEquals("https://example.com/from-guid", FeedParser.parse(feed, "ko").get(0).canonicalUrl());
    }
}
