package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * FEED 소스 하나를 읽어 기사 목록으로 돌려준다.
 *
 * <p><b>예외를 던지지 않는다.</b> 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
 * 대신 성공과 실패를 {@link FetchResult}로 구분해서 돌려준다 — 빈 목록만 주면 호출부가
 * "기사가 0건인 피드"와 "읽기 실패"를 구분할 수 없어, 404가 난 소스도 SUCCESS 0건으로 기록된다.
 *
 * <p>robots.txt 확인과 Conditional GET(304), 도메인별 rate limit은 F6에서 이 앞에 붙는다.
 */
@Slf4j
@Component
public class FeedClient {

    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;

    public FeedClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public FetchResult fetch(String feedUrl, String language) {
        String body;
        try {
            body = restClient.get()
                    .uri(feedUrl)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            return failureOf(feedUrl, e);
        }

        try {
            return FetchResult.ok(FeedParser.parse(body, language));
        } catch (FeedParseException e) {
            log.warn("피드를 읽지 못했다. HTML 페이지를 FEED로 등록했을 수 있다. url={} error={}",
                    feedUrl, e.getMessage());
            return FetchResult.unreadable(e.getMessage());
        }
    }

    /**
     * 4xx는 다시 불러도 같은 답이라 여기서 멈춘다. 429·5xx는 F6이 붙을 재시도 대상이라 갈라서 남긴다.
     */
    private FetchResult failureOf(String feedUrl, RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            boolean retryable = response.getStatusCode().value() == TOO_MANY_REQUESTS
                    || response.getStatusCode().is5xxServerError();

            if (retryable) {
                log.warn("피드를 일시적으로 읽지 못했다. F6 재시도 대상이다. status={} url={}",
                        response.getStatusCode(), feedUrl);
                return FetchResult.rateLimited("피드 응답 " + response.getStatusCode());
            }

            log.warn("피드 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} url={}",
                    response.getStatusCode(), feedUrl);
            return FetchResult.unreadable("피드 응답 " + response.getStatusCode());
        }

        log.warn("피드 호출에 실패했다. url={} error={}", feedUrl, exception.getMessage());
        return FetchResult.unreadable("피드 호출 실패: " + exception.getMessage());
    }
}
