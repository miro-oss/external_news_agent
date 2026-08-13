package com.example.be.domain.collection.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(8);

    /**
     * 4xx를 재시도하면 실패를 3배 느리게 알게 될 뿐이다.
     */
    @Test
    void retriesOnlyRateLimitAndServerErrors() {
        assertTrue(Backoff.isRetryable(HttpStatus.TOO_MANY_REQUESTS));
        assertTrue(Backoff.isRetryable(HttpStatus.SERVICE_UNAVAILABLE));
        assertTrue(Backoff.isRetryable(HttpStatus.INTERNAL_SERVER_ERROR));

        assertFalse(Backoff.isRetryable(HttpStatus.UNAUTHORIZED));
        assertFalse(Backoff.isRetryable(HttpStatus.NOT_FOUND));
        assertFalse(Backoff.isRetryable(HttpStatus.BAD_REQUEST));
    }

    @Test
    void doublesDelayEachAttempt() {
        assertEquals(Duration.ofSeconds(1), Backoff.delayAfter(1, BASE, MAX));
        assertEquals(Duration.ofSeconds(2), Backoff.delayAfter(2, BASE, MAX));
        assertEquals(Duration.ofSeconds(4), Backoff.delayAfter(3, BASE, MAX));
    }

    @Test
    void stopsAtMaxDelay() {
        assertEquals(MAX, Backoff.delayAfter(10, BASE, MAX));
        assertEquals(MAX, Backoff.delayAfter(100, BASE, MAX));
    }

    @Test
    void returnsZeroBeforeFirstAttempt() {
        assertEquals(Duration.ZERO, Backoff.delayAfter(0, BASE, MAX));
    }
}
