package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ratelimit.Backoff;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;

/**
 * 기사 URL을 방문해 HTML을 받고 본문을 뽑는다.
 *
 * <p>피드 하나당 한 번이던 요청이 <b>기사 수만큼</b>으로 늘어나는 자리다. F6의 도메인 간격과 백오프를
 * 그대로 얹는 이유가 이것이다. 예외는 던지지 않고 {@link ArticleContentResult}로 사유를 돌려준다.
 */
@Slf4j
@Component
public class ArticleContentClient {

    /**
     * 기사 페이지는 robots.txt보다 크지만 무한정은 아니다. <b>상한까지만 읽는다</b> — 다 받아 놓고 크기를
     * 재면 이미 메모리에 올라온 뒤라 보호가 되지 않는다.
     */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int UNAVAILABLE_FOR_LEGAL_REASONS = 451;

    private final RestClient restClient;
    private final DomainRateLimiter rateLimiter;
    private final String userAgent;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public ArticleContentClient(RestClient.Builder restClientBuilder,
                                DomainRateLimiter rateLimiter,
                                @Value("${news.collection.user-agent:external-news-agent}") String userAgent,
                                @Value("${news.collection.retry.max-attempts:3}") int maxAttempts,
                                @Value("${news.collection.retry.base-delay-ms:1000}") long backoffBaseMs,
                                @Value("${news.collection.retry.max-delay-ms:8000}") long backoffMaxMs) {
        this.restClient = restClientBuilder.build();
        this.rateLimiter = rateLimiter;
        this.userAgent = userAgent;
        this.maxAttempts = maxAttempts;
        this.backoffBase = Duration.ofMillis(backoffBaseMs);
        this.backoffMax = Duration.ofMillis(backoffMaxMs);
    }

    public ArticleContentResult fetch(String articleUrl, Duration crawlDelay) {
        Attempt attempt = null;

        for (int tries = 1; tries <= maxAttempts; tries++) {
            rateLimiter.await(articleUrl, crawlDelay);

            try {
                attempt = restClient.get()
                        .uri(articleUrl)
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        // 상태 처리를 직접 한다. 본문을 읽기 전에 헤더로 크기를 먼저 보려면 이 방법뿐이다.
                        // 닫는 건 Spring에 맡긴다 — close=false로 두면 본문을 읽지 않는 차단·에러·크기 초과
                        // 경로에서 커넥션이 풀로 돌아가지 못한다. 여기가 기사 수만큼 도는 자리라 더 빨리 마른다.
                        .exchange((request, response) -> read(articleUrl, response));
            } catch (RuntimeException e) {
                log.debug("본문 호출에 실패했다. url={} error={}", articleUrl, e.getMessage());
                attempt = new Attempt(ArticleContentResult.failed(), true);
            }

            if (!attempt.retryable() || tries == maxAttempts) {
                return attempt.result();
            }

            sleep(Backoff.delayAfter(tries, backoffBase, backoffMax));
        }

        return attempt == null ? ArticleContentResult.failed() : attempt.result();
    }

    private Attempt read(String articleUrl, org.springframework.http.client.ClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();

        if (isPaywall(status)) {
            // 상대가 명시적으로 막은 경우만 차단으로 본다. 재시도해도 같은 답이다.
            log.debug("본문이 막혀 있다. status={} url={}", status, articleUrl);
            return new Attempt(ArticleContentResult.blocked(), false);
        }

        if (status.isError()) {
            return new Attempt(ArticleContentResult.failed(), Backoff.isRetryable(status));
        }

        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            log.warn("기사 본문이 너무 커서 읽지 않는다. url={}", articleUrl);
            return new Attempt(ArticleContentResult.failed(), false);
        }

        // 상한 + 1까지만 읽는다. 넘치면 그 이상은 메모리에 올리지 않는다.
        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            log.warn("기사 본문이 너무 커서 읽지 않는다. url={}", articleUrl);
            return new Attempt(ArticleContentResult.failed(), false);
        }

        String content = ArticleContentExtractor.extract(body, charsetOf(response), articleUrl);
        if (content == null) {
            // 응답은 정상인데 본문을 못 뽑았다. 이건 "막혔다"가 아니라 "못 읽었다"이다.
            // 차단으로 적으면 짧은 정상 기사가 페이월 경고를 만든다.
            log.debug("본문을 뽑지 못했다. url={}", articleUrl);
            return new Attempt(ArticleContentResult.failed(), false);
        }

        return new Attempt(ArticleContentResult.fullText(content), false);
    }

    /**
     * HTTP 헤더의 charset이 meta 태그보다 우선이다. {@code charset=EUC-KR}을 헤더에만 적어 두고
     * meta에는 없는 매체가 있어, 이걸 버리면 본문이 깨진다.
     */
    private String charsetOf(org.springframework.http.client.ClientHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || contentType.getCharset() == null) {
            return null;
        }

        Charset charset = contentType.getCharset();
        return charset.name();
    }

    private boolean isPaywall(HttpStatusCode status) {
        int value = status.value();
        return value == UNAUTHORIZED || value == FORBIDDEN || value == UNAVAILABLE_FOR_LEGAL_REASONS;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 한 번의 시도 결과와, 다시 불러 볼 가치가 있는지. */
    private record Attempt(ArticleContentResult result, boolean retryable) {
    }
}
