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

    static RobotsDecision skipped(Source source) {
        return new RobotsDecision(true, source.getRobotsStatus(), source.getRobotsCheckedAt(), null, null, null);
    }

    public boolean resolved() {
        return failureReason == null;
    }

    public Long crawlDelaySeconds() {
        return crawlDelay == null ? null : crawlDelay.toSeconds();
    }
}
