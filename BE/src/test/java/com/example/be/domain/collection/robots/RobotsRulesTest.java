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

    /**
     * 연달아 붙은 User-agent 줄은 하나의 그룹이고 규칙을 공유한다. 줄마다 덮어쓰면
     * "우리" 다음에 "남"이 오는 순간 우리에게 적용될 금지 규칙을 놓친다.
     */
    @Test
    void sharesRulesAcrossUserAgentsInOneGroup() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: external-news-agent
                User-agent: another-bot
                Disallow: /private/
                """, "external-news-agent");

        assertFalse(rules.allows("https://example.com/private/x"));
    }

    /**
     * 규칙 줄을 지나 다시 User-agent가 나오면 새 그룹이다. 앞 그룹의 매칭이 이어지면 안 된다.
     */
    @Test
    void startsNewGroupAfterRuleLine() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: external-news-agent
                Disallow: /mine/
                User-agent: another-bot
                Disallow: /theirs/
                """, "external-news-agent");

        assertFalse(rules.allows("https://example.com/mine/x"));
        assertTrue(rules.allows("https://example.com/theirs/x"));
    }

    /**
     * 0.5초를 내림하면 0이 되고, 응답의 0은 "간격이 없다"로 읽힌다.
     */
    @Test
    void roundsSubSecondCrawlDelayUp() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Crawl-delay: 0.5
                """, "external-news-agent");
        RobotsDecision decision = new RobotsDecision(true, "allowed", null, null, rules.crawlDelay(), null);

        assertEquals(Duration.ofMillis(500), rules.crawlDelay());
        assertEquals(1L, decision.crawlDelaySeconds());
    }

    @Test
    void permitsEverythingWhenFileIsEmpty() {
        assertTrue(RobotsRules.parse("", "external-news-agent").allows("https://example.com/x"));
        assertTrue(RobotsRules.permitAll().allows("https://example.com/x"));
    }
}
