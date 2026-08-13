package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * FEED 소스 하나를 읽어 기사 목록으로 돌려준다.
 *
 * <p>검색 커넥터와 같은 계약이다 — <b>어떤 이유로든 예외를 던지지 않고 빈 목록을 돌려준다.</b>
 * 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
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

    public List<CollectedArticle> fetch(String feedUrl, String language) {
        try {
            String body = restClient.get()
                    .uri(feedUrl)
                    .retrieve()
                    .body(String.class);

            List<CollectedArticle> articles = FeedParser.parse(body, language);
            if (articles.isEmpty()) {
                log.warn("피드에서 기사를 하나도 읽지 못했다. HTML 페이지를 FEED로 등록했을 수 있다. url={}", feedUrl);
            }

            return articles;
        } catch (RestClientException e) {
            return emptyOnFailure(feedUrl, e);
        }
    }

    /**
     * 4xx는 다시 불러도 같은 답이라 여기서 멈춘다. 429·5xx는 F6이 붙을 재시도 대상이라 갈라서 남긴다.
     */
    private List<CollectedArticle> emptyOnFailure(String feedUrl, RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            boolean retryable = response.getStatusCode().value() == TOO_MANY_REQUESTS
                    || response.getStatusCode().is5xxServerError();

            if (retryable) {
                log.warn("피드를 일시적으로 읽지 못했다. M3 재시도 대상이다. status={} url={}",
                        response.getStatusCode(), feedUrl);
            } else {
                log.warn("피드 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} url={}",
                        response.getStatusCode(), feedUrl);
            }
        } else {
            log.warn("피드 호출에 실패했다. url={} error={}", feedUrl, exception.getMessage());
        }

        return List.of();
    }
}
