package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.ratelimit.Backoff;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;

/**
 * FEED 소스 하나를 읽는다.
 *
 * <p><b>예외를 던지지 않는다.</b> 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
 * 성공과 실패는 {@link FetchResult}로 구분해서 돌려준다.
 *
 * <p>F6이 여기에 세 가지를 얹었다 — 도메인별 간격, 조건부 GET, 429·5xx 지수 백오프.
 */
@Slf4j
@Component
public class FeedClient {

    /**
     * 남이 주는 응답이라 크기를 신뢰할 수 없다. robots.txt는 512KiB, 기사 본문은 2MiB 상한이 있는데
     * 피드만 없었다(#32 C2). 피드는 헤더+요약 목록이라 본문보다 작다 — 넘으면 정상적인 피드가 아니다.
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final RestClient restClient;
    private final DomainRateLimiter rateLimiter;
    private final String userAgent;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public FeedClient(RestClient.Builder restClientBuilder,
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

    public FeedFetch fetch(FeedRequest request) {
        Attempt attempt = null;

        for (int tries = 1; tries <= maxAttempts; tries++) {
            rateLimiter.await(request.feedUrl(), request.crawlDelay());

            try {
                attempt = exchange(request);
            } catch (RestClientException e) {
                log.warn("피드 호출에 실패했다. url={} error={}", request.feedUrl(), e.getMessage());
                attempt = Attempt.terminal(FeedFetch.of(
                        FetchResult.unreadable("피드 호출 실패: " + e.getMessage())));
            }

            if (!attempt.retryable() || tries == maxAttempts) {
                return attempt.fetch();
            }

            Duration delay = Backoff.delayAfter(tries, backoffBase, backoffMax);
            log.warn("피드를 일시적으로 읽지 못해 다시 시도한다. attempt={}/{} delayMs={} url={}",
                    tries, maxAttempts, delay.toMillis(), request.feedUrl());
            sleep(delay);
        }

        return attempt.fetch();
    }

    /**
     * 상태 처리를 직접 한다. 본문을 읽기 전에 헤더로 크기를 먼저 보려면 이 방법뿐이다.
     *
     * <p>응답은 Spring이 닫게 둔다({@code close} 기본값 {@code true}). {@code false}로 두면
     * <b>닫는 책임이 호출자에게 넘어오는데</b>, 304·에러·크기 초과 경로는 본문을 읽지도 않고 빠져나가서
     * 커넥션이 풀로 돌아가지 못한다. {@link #read}가 {@code byte[]}를 다 읽고 파싱까지 끝낸 뒤
     * 리턴하므로 콜백 이후에 스트림이 필요한 곳은 없다.
     */
    private Attempt exchange(FeedRequest request) {
        return restClient.get()
                .uri(request.feedUrl())
                .header(HttpHeaders.USER_AGENT, userAgent)
                .headers(headers -> {
                    if (StringUtils.hasText(request.etag())) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, request.etag());
                    }
                    if (StringUtils.hasText(request.lastModified())) {
                        headers.set(HttpHeaders.IF_MODIFIED_SINCE, request.lastModified());
                    }
                })
                .exchange((httpRequest, response) -> read(request, response));
    }

    private Attempt read(FeedRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);

        // 304는 실패가 아니다. 바뀐 게 없다는 뜻이고, 그게 조건부 GET의 목적이다.
        if (status.value() == HttpStatus.NOT_MODIFIED.value()) {
            log.debug("바뀐 게 없어 파싱을 건너뛴다. url={}", request.feedUrl());
            return Attempt.terminal(FeedFetch.notModified(
                    etag != null ? etag : request.etag(),
                    lastModified != null ? lastModified : request.lastModified()));
        }

        if (status.isError()) {
            return errorOf(request.feedUrl(), status);
        }

        byte[] body = readWithinLimit(request.feedUrl(), response);
        if (body == null) {
            return Attempt.terminal(FeedFetch.of(FetchResult.unreadable("피드가 너무 크다")));
        }

        try {
            return Attempt.terminal(new FeedFetch(
                    FetchResult.ok(FeedParser.parse(body, request.language())), false, etag, lastModified));
        } catch (FeedParseException e) {
            log.warn("피드를 읽지 못했다. HTML 페이지를 FEED로 등록했을 수 있다. url={} error={}",
                    request.feedUrl(), e.getMessage());
            return Attempt.terminal(FeedFetch.of(FetchResult.unreadable(e.getMessage())));
        }
    }

    /**
     * {@code Content-Length}가 없는 응답(chunked)이 흔하다. 헤더만 믿으면 상한이 없는 것과 같아서,
     * 상한 + 1까지만 읽어 실제로 넘치는지 본다. 넘치면 그 이상은 메모리에 올리지 않는다.
     *
     * @return 상한을 넘으면 {@code null}
     */
    private byte[] readWithinLimit(String feedUrl, ClientHttpResponse response) throws IOException {
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            log.warn("피드가 너무 커서 읽지 않는다. contentLength={} url={}",
                    response.getHeaders().getContentLength(), feedUrl);
            return null;
        }

        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            log.warn("피드가 너무 커서 읽지 않는다. maxBytes={} url={}", MAX_BODY_BYTES, feedUrl);
            return null;
        }
        return body;
    }

    /**
     * 4xx는 다시 불러도 같은 답이라 여기서 멈춘다. 429·5xx는 재시도 대상이고, 다 쓰고도 실패하면
     * 호출자에게는 rate limit으로 알린다.
     */
    private Attempt errorOf(String feedUrl, HttpStatusCode status) {
        if (Backoff.isRetryable(status)) {
            return new Attempt(FeedFetch.of(FetchResult.rateLimited("피드 응답 " + status)), true);
        }

        log.warn("피드 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} url={}", status, feedUrl);
        return Attempt.terminal(FeedFetch.of(FetchResult.unreadable("피드 응답 " + status)));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 한 번의 시도 결과와, 다시 불러 볼 가치가 있는지. */
    private record Attempt(FeedFetch fetch, boolean retryable) {

        private static Attempt terminal(FeedFetch fetch) {
            return new Attempt(fetch, false);
        }
    }
}
