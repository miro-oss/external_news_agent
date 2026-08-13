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
        assertEquals(Duration.ZERO, limiter.reserve("https://www.hankyung.com/feed/economy", null));
    }

    @Test
    void waitsForTheRemainderOfTheInterval() {
        limiter.reserve("https://www.hankyung.com/feed/economy", null);
        clock.advance(Duration.ofMillis(400));

        assertEquals(Duration.ofMillis(600), limiter.reserve("https://www.hankyung.com/feed/it", null));
    }

    /**
     * 시드 17건 중 한국경제가 4건이다. 도메인이 다르면 서로 기다릴 이유가 없다.
     */
    @Test
    void tracksEachDomainSeparately() {
        limiter.reserve("https://www.hankyung.com/feed/economy", null);

        assertEquals(Duration.ZERO, limiter.reserve("https://www.mk.co.kr/rss/50000001/", null));
        assertTrue(limiter.reserve("https://www.hankyung.com/feed/it", null).toMillis() > 0);
    }

    /**
     * 슬롯을 원자적으로 잡지 않으면 스레드 둘이 같은 대기 시간을 계산하고 함께 깨어나 동시에 요청한다.
     * 시간을 전혀 흘리지 않고 연속으로 잡아도 간격이 누적돼야 한다.
     */
    @Test
    void stacksReservationsWithoutAdvancingTime() {
        assertEquals(Duration.ZERO, limiter.reserve("https://example.com/feed", null));
        assertEquals(Duration.ofSeconds(1), limiter.reserve("https://example.com/feed", null));
        assertEquals(Duration.ofSeconds(2), limiter.reserve("https://example.com/feed", null));
    }

    /**
     * 남의 서버가 직접 말한 값이 우리 기본값보다 우선한다.
     */
    @Test
    void prefersCrawlDelayOverDefault() {
        limiter.reserve("https://example.com/feed", Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(5), limiter.reserve("https://example.com/feed", Duration.ofSeconds(5)));
    }

    /**
     * Crawl-delay: 3600을 그대로 지키면 수집 실행이 그 소스 하나에 묶인다.
     */
    @Test
    void capsAbsurdCrawlDelay() {
        limiter.reserve("https://example.com/feed", Duration.ofHours(1));

        assertEquals(MAX_INTERVAL, limiter.reserve("https://example.com/feed", Duration.ofHours(1)));
    }

    @Test
    void doesNotWaitAfterIntervalPassed() {
        limiter.reserve("https://example.com/feed", null);
        clock.advance(Duration.ofSeconds(2));

        assertEquals(Duration.ZERO, limiter.reserve("https://example.com/feed", null));
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
