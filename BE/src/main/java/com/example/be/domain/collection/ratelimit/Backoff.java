package com.example.be.domain.collection.ratelimit;

import org.springframework.http.HttpStatusCode;

import java.time.Duration;

/**
 * 재시도 간격. 실패하자마자 같은 속도로 다시 두드리면 상대 서버를 더 밀어붙이게 된다.
 *
 * <p>재시도 대상은 <b>429와 5xx뿐</b>이다. 4xx는 몇 번을 다시 불러도 같은 답이 온다 —
 * 키가 틀렸는데 3번 재시도하면 실패를 3배 느리게 알게 될 뿐이다
 * ({@code docs/study-naver-integration.md} §6-1(3)).
 */
public final class Backoff {

    public static final int TOO_MANY_REQUESTS = 429;

    private Backoff() {
    }

    public static boolean isRetryable(HttpStatusCode status) {
        return status.value() == TOO_MANY_REQUESTS || status.is5xxServerError();
    }

    /**
     * 1회차 실패 뒤 base, 2회차 뒤 2×base, 3회차 뒤 4×base. 상한을 넘지 않는다.
     *
     * @param attempt 1부터 센 시도 횟수
     */
    public static Duration delayAfter(int attempt, Duration base, Duration max) {
        if (attempt < 1) {
            return Duration.ZERO;
        }

        // 지수가 커지면 곱셈이 넘칠 수 있어 상한에 닿는 순간 멈춘다.
        Duration delay = base;
        for (int i = 1; i < attempt && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }

        return delay.compareTo(max) > 0 ? max : delay;
    }
}
