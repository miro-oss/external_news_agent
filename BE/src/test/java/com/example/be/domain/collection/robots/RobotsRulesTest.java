package com.example.be.domain.collection.robots;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotsRulesTest {

    @Test
    void appliesWildcardBlock() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /admin/
                """, "external-news-agent");

        assertFalse(rules.allows("https://example.com/admin/list"));
        assertTrue(rules.allows("https://example.com/feed/economy"));
    }

    @Test
    void appliesBlockForOurUserAgent() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: some-other-bot
                Disallow: /
                User-agent: external-news-agent
                Disallow: /private/
                """, "external-news-agent");

        assertTrue(rules.allows("https://example.com/feed/economy"));
        assertFalse(rules.allows("https://example.com/private/x"));
    }

    /**
     * "Disallow:" 뒤가 비어 있으면 아무것도 막지 않는다는 뜻이다. 전체 차단으로 읽으면 매체를 통째로 잃는다.
     */
    @Test
    void treatsEmptyDisallowAsNoRestriction() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow:
                """, "external-news-agent");

        assertTrue(rules.allows("https://example.com/anything"));
    }

    /**
     * 더 긴 규칙이 이긴다. 전체를 막고 피드만 여는 robots.txt가 흔하다.
     */
    @Test
    void letsLongerAllowWinOverDisallow() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /
                Allow: /rss/
                """, "external-news-agent");

        assertTrue(rules.allows("https://example.com/rss/economy.xml"));
        assertFalse(rules.allows("https://example.com/news/1"));
    }

    @Test
    void understandsWildcardAndAnchor() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /*/private
                Disallow: /tmp$
                """, "external-news-agent");

        assertFalse(rules.allows("https://example.com/a/private"));
        assertFalse(rules.allows("https://example.com/tmp"));
        assertTrue(rules.allows("https://example.com/tmp/keep"));
    }

    @Test
    void readsCrawlDelay() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Crawl-delay: 5
                """, "external-news-agent");

        assertTrue(rules.hasCrawlDelay());
        assertEquals(Duration.ofSeconds(5), rules.crawlDelay());
    }

    /**
     * robots.txt는 표준이 아니라 관례에 가깝다. 이상한 줄 하나에 파서가 죽으면 매체를 잃는다.
     */
    @Test
    void survivesGarbageLines() {
        RobotsRules rules = RobotsRules.parse("""
                # 주석
                Sitemap: https://example.com/sitemap.xml
                User-agent: *
                Crawl-delay: 안녕
                Disallow: /admin/   # 뒤에 붙은 주석
                이상한 줄
                """, "external-news-agent");

        assertNull(rules.crawlDelay());
        assertFalse(rules.allows("https://example.com/admin/x"));
        assertTrue(rules.allows("https://example.com/feed"));
    }

    @Test
    void permitsEverythingWhenFileIsEmpty() {
        assertTrue(RobotsRules.parse("", "external-news-agent").allows("https://example.com/x"));
        assertTrue(RobotsRules.permitAll().allows("https://example.com/x"));
    }
}
