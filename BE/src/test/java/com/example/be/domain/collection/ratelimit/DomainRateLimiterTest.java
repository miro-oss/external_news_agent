package com.example.be.domain.collection.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainRateLimiterTest {

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
    private final DomainRateLimiter limiter = new DomainRateLimiter(DEFAULT_INTERVAL, MAX_INTERVAL, clock);

    @Test
    void doesNotWaitForTheFirstRequest() {
        assertEquals(Duration.ZERO, limiter.waitFor("https://www.hankyung.com/feed/economy", null));
    }

    @Test
    void waitsForTheRemainderOfTheInterval() {
        limiter.recordRequest("https://www.hankyung.com/feed/economy");
        clock.advance(Duration.ofMillis(400));

        assertEquals(Duration.ofMillis(600), limiter.waitFor("https://www.hankyung.com/feed/it", null));
    }

    /**
     * 시드 17건 중 한국경제가 4건이다. 도메인이 다르면 서로 기다릴 이유가 없다.
     */
    @Test
    void tracksEachDomainSeparately() {
        limiter.recordRequest("https://www.hankyung.com/feed/economy");

        assertEquals(Duration.ZERO, limiter.waitFor("https://www.mk.co.kr/rss/50000001/", null));
        assertTrue(limiter.waitFor("https://www.hankyung.com/feed/it", null).toMillis() > 0);
    }

    /**
     * 남의 서버가 직접 말한 값이 우리 기본값보다 우선한다.
     */
    @Test
    void prefersCrawlDelayOverDefault() {
        limiter.recordRequest("https://example.com/feed");

        assertEquals(Duration.ofSeconds(5), limiter.waitFor("https://example.com/feed", Duration.ofSeconds(5)));
    }

    /**
     * Crawl-delay: 3600을 그대로 지키면 수집 실행이 그 소스 하나에 묶인다.
     */
    @Test
    void capsAbsurdCrawlDelay() {
        limiter.recordRequest("https://example.com/feed");

        assertEquals(MAX_INTERVAL, limiter.waitFor("https://example.com/feed", Duration.ofHours(1)));
    }

    @Test
    void doesNotWaitAfterIntervalPassed() {
        limiter.recordRequest("https://example.com/feed");
        clock.advance(Duration.ofSeconds(2));

        assertEquals(Duration.ZERO, limiter.waitFor("https://example.com/feed", null));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
