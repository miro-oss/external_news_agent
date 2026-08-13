package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ratelimit.Backoff;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
     * 기사 페이지는 robots.txt보다 크지만 무한정은 아니다. 상한이 없으면 응답 하나로 메모리를 밀어낼 수 있다.
     */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

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
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            rateLimiter.await(articleUrl, crawlDelay);

            try {
                return handle(articleUrl, restClient.get()
                        .uri(articleUrl)
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        .retrieve()
                        .toEntity(byte[].class));
            } catch (RestClientResponseException e) {
                if (isPaywall(e)) {
                    // 401·403은 "막았다"이다. 재시도해도 같은 답이고, 명세가 FULLTEXT_BLOCKED로 부르는 경우다.
                    log.debug("본문이 막혀 있다. status={} url={}", e.getStatusCode(), articleUrl);
                    return ArticleContentResult.blocked();
                }

                if (!Backoff.isRetryable(e.getStatusCode()) || attempt == maxAttempts) {
                    log.debug("본문을 받지 못했다. status={} url={}", e.getStatusCode(), articleUrl);
                    return ArticleContentResult.failed();
                }

                sleep(Backoff.delayAfter(attempt, backoffBase, backoffMax));
            } catch (RestClientException e) {
                log.debug("본문 호출에 실패했다. url={} error={}", articleUrl, e.getMessage());
                return ArticleContentResult.failed();
            }
        }

        return ArticleContentResult.failed();
    }

    private ArticleContentResult handle(String articleUrl, ResponseEntity<byte[]> response) {
        if (isTooLarge(response)) {
            log.warn("기사 본문이 너무 커서 읽지 않는다. url={}", articleUrl);
            return ArticleContentResult.failed();
        }

        String body = ArticleContentExtractor.extract(response.getBody(), articleUrl);
        if (body == null) {
            // 응답은 왔는데 본문이 없다. 페이월이 로그인 안내만 주는 경우가 대부분이다.
            return ArticleContentResult.blocked();
        }

        return ArticleContentResult.fullText(body);
    }

    private boolean isPaywall(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == 401 || status == 403 || status == 451;
    }

    private boolean isTooLarge(ResponseEntity<byte[]> response) {
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            return true;
        }

        byte[] body = response.getBody();
        return body != null && body.length > MAX_BODY_BYTES;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
