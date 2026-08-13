package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.ratelimit.Backoff;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
        RestClientException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            rateLimiter.await(request.feedUrl(), request.crawlDelay());

            try {
                return handle(request, exchange(request));
            } catch (RestClientResponseException e) {
                lastFailure = e;
                if (!Backoff.isRetryable(e.getStatusCode()) || attempt == maxAttempts) {
                    break;
                }

                Duration delay = Backoff.delayAfter(attempt, backoffBase, backoffMax);
                log.warn("피드를 일시적으로 읽지 못해 다시 시도한다. status={} attempt={}/{} delayMs={} url={}",
                        e.getStatusCode(), attempt, maxAttempts, delay.toMillis(), request.feedUrl());
                sleep(delay);
            } catch (RestClientException e) {
                lastFailure = e;
                break;
            }
        }

        return FeedFetch.of(failureOf(request.feedUrl(), lastFailure));
    }

    private ResponseEntity<String> exchange(FeedRequest request) {
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
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * 304는 실패가 아니다. 바뀐 게 없다는 뜻이고, 그게 조건부 GET의 목적이다.
     */
    private FeedFetch handle(FeedRequest request, ResponseEntity<String> response) {
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);

        if (response.getStatusCode().value() == HttpStatus.NOT_MODIFIED.value()) {
            log.debug("바뀐 게 없어 파싱을 건너뛴다. url={}", request.feedUrl());
            return FeedFetch.notModified(etag != null ? etag : request.etag(),
                    lastModified != null ? lastModified : request.lastModified());
        }

        try {
            return new FeedFetch(FetchResult.ok(FeedParser.parse(response.getBody(), request.language())),
                    false, etag, lastModified);
        } catch (FeedParseException e) {
            log.warn("피드를 읽지 못했다. HTML 페이지를 FEED로 등록했을 수 있다. url={} error={}",
                    request.feedUrl(), e.getMessage());
            return FeedFetch.of(FetchResult.unreadable(e.getMessage()));
        }
    }

    /**
     * 4xx는 다시 불러도 같은 답이라 여기서 멈춘다. 429·5xx로 여기 왔다면 재시도를 이미 다 쓴 것이다.
     */
    private FetchResult failureOf(String feedUrl, RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            if (Backoff.isRetryable(response.getStatusCode())) {
                log.warn("재시도를 다 썼는데도 피드를 읽지 못했다. status={} url={}",
                        response.getStatusCode(), feedUrl);
                return FetchResult.rateLimited("피드 응답 " + response.getStatusCode());
            }

            log.warn("피드 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} url={}",
                    response.getStatusCode(), feedUrl);
            return FetchResult.unreadable("피드 응답 " + response.getStatusCode());
        }

        String message = exception == null ? "알 수 없는 오류" : exception.getMessage();
        log.warn("피드 호출에 실패했다. url={} error={}", feedUrl, message);
        return FetchResult.unreadable("피드 호출 실패: " + message);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
