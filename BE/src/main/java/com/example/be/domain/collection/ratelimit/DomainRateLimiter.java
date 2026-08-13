package com.example.be.domain.collection.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 도메인에 연달아 요청하지 않도록 간격을 둔다.
 *
 * <p>시드 17건 중 한국경제가 4건, 매일경제가 3건이다. 한 실행에서 같은 서버를 연속으로 두드리면
 * 차단당하고, 차단당하면 수집이 통째로 멈춘다.
 *
 * <p>간격의 기준은 <b>robots.txt의 {@code Crawl-delay}가 우선</b>이다. 남의 서버가 직접 말한 값이
 * 우리 기본값보다 우선한다.
 */
@Slf4j
@Component
public class DomainRateLimiter {

    private final Map<String, Instant> lastRequestAt = new ConcurrentHashMap<>();
    private final Duration defaultInterval;
    private final Duration maxInterval;
    private final Clock clock;

    // 생성자가 둘이라 Spring이 쓸 쪽을 지정한다. 나머지 하나는 테스트에서 시계를 넣기 위한 것이다.
    @Autowired
    public DomainRateLimiter(@Value("${news.collection.rate-limit.default-interval-ms:1000}") long defaultIntervalMs,
                             @Value("${news.collection.rate-limit.max-interval-ms:30000}") long maxIntervalMs) {
        this(Duration.ofMillis(defaultIntervalMs), Duration.ofMillis(maxIntervalMs), Clock.systemUTC());
    }

    DomainRateLimiter(Duration defaultInterval, Duration maxInterval, Clock clock) {
        this.defaultInterval = defaultInterval;
        this.maxInterval = maxInterval;
        this.clock = clock;
    }

    /**
     * 다음 요청까지 기다려야 하는 시간. 계산과 대기를 나눠 둔 이유는 테스트에서 실제로 재우지 않기 위해서다.
     */
    public Duration waitFor(String url, Duration crawlDelay) {
        String host = hostOf(url);
        if (host == null) {
            return Duration.ZERO;
        }

        Instant last = lastRequestAt.get(host);
        if (last == null) {
            return Duration.ZERO;
        }

        Duration interval = intervalFor(crawlDelay);
        Duration elapsed = Duration.between(last, clock.instant());
        Duration remaining = interval.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 간격이 찰 때까지 기다리고 요청 시각을 남긴다.
     */
    public void await(String url, Duration crawlDelay) {
        Duration wait = waitFor(url, crawlDelay);

        if (!wait.isZero()) {
            log.debug("도메인 간격을 지키려고 대기한다. host={} waitMs={}", hostOf(url), wait.toMillis());
            sleep(wait);
        }

        recordRequest(url);
    }

    public void recordRequest(String url) {
        String host = hostOf(url);
        if (host != null) {
            lastRequestAt.put(host, clock.instant());
        }
    }

    /**
     * 상대가 말한 Crawl-delay를 지키되 상한을 둔다. 한 소스가 {@code Crawl-delay: 3600}을 적어 두면
     * 수집 실행 전체가 그 소스 하나에 묶인다.
     */
    private Duration intervalFor(Duration crawlDelay) {
        if (crawlDelay == null || crawlDelay.isZero() || crawlDelay.isNegative()) {
            return defaultInterval;
        }

        return crawlDelay.compareTo(maxInterval) > 0 ? maxInterval : crawlDelay;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
