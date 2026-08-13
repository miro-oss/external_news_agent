package com.example.be.domain.collection.robots;

import com.example.be.domain.sources.entity.Source;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * robots 확인 결과. 수집 엔진과 재확인 API가 같이 쓴다.
 *
 * <p>{@code allowed}가 false인 경우는 <b>robots.txt가 실제로 막았을 때뿐</b>이다.
 * 못 받았거나 확인을 건너뛴 경우는 허용으로 둔다 — 근거 없이 수집을 멈추면 robots.txt가 없는 매체를 잃는다.
 */
public record RobotsDecision(boolean allowed,
                             String robotsStatus,
                             LocalDateTime checkedAt,
                             String robotsTxtUrl,
                             Duration crawlDelay,
                             String failureReason) {

    /**
     * 판정을 소스에 적는다. 확인을 건너뛴 경우(robotsMode=ignore)에는 상태를 덮지 않는다.
     */
    public void applyTo(Source source) {
        if (checkedAt != null && robotsStatus != null) {
            source.applyRobotsCheck(robotsStatus, checkedAt);
        }
    }

    /** 확인하지 않았다는 뜻. SEARCH 소스와 robotsMode=ignore가 여기로 온다. */
    public static RobotsDecision skipped(Source source) {
        return new RobotsDecision(true, source.getRobotsStatus(), source.getRobotsCheckedAt(), null, null, null);
    }

    public boolean resolved() {
        return failureReason == null;
    }

    /**
     * 초 단위로 올림한다. {@code Crawl-delay: 0.5}를 내림하면 0이 되는데, 응답의 0은 "간격이 없다"로 읽힌다.
     */
    public Long crawlDelaySeconds() {
        if (crawlDelay == null) {
            return null;
        }

        return (crawlDelay.toMillis() + 999) / 1000;
    }
}
