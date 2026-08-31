package com.example.be.domain.collection.cluster;

import java.time.Duration;
import java.time.OffsetDateTime;

public record BreakingWatchAlert(
        Long watchId,
        Long notifyGroupId,
        String issueTitle,
        OffsetDateTime firstSeenAt,
        int followUpCount,
        int publisherCount,
        OffsetDateTime claimedAt
) {

    public String message() {
        long elapsedHours = Math.max(0, Duration.between(firstSeenAt, claimedAt).toHours());
        String elapsed = elapsedHours == 0 ? "방금" : elapsedHours + "시간 전";
        return "%s 속보 '%s'에 후속 %d건 · 매체 %d곳 확인됨"
                .formatted(elapsed, issueTitle, followUpCount, publisherCount);
    }
}
