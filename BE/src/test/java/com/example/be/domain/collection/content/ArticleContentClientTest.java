package com.example.be.domain.collection.content;

import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ArticleContentClientTest {

    private static final String ARTICLE_URL = "https://www.hankyung.com/article/1";

    private static final String PARAGRAPH =
            "삼성전자가 HBM4 양산 일정을 앞당기기로 했다. 업계에 따르면 이번 결정은 고객사 요구를 반영한 것이다. ".repeat(3);

    private static final String HTML = """
            <html><body><article><p>%s</p><p>%s</p></article></body></html>
            """.formatted(PARAGRAPH, PARAGRAPH);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    // 재시도 1회·지연 0으로 둬서 테스트가 실제로 잠들지 않게 한다.
    private final DomainRateLimiter rateLimiter = new DomainRateLimiter(0L, 0L);
    private final ArticleContentClient client =
            new ArticleContentClient(builder, rateLimiter, "external-news-agent", 1, 0L, 0L);

    @Test
    void extractsFullText() {
        server.expect(requestTo(ARTICLE_URL))
                .andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null);

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertTrue(result.body().contains("HBM4 양산 일정"));
        server.verify();
    }

    /**
     * 401·403은 "막았다"이다. 재시도해도 같은 답이고, 명세가 FULLTEXT_BLOCKED로 부르는 경우다.
     */
    @Test
    void reportsBlockedForPaywallStatus() {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null);

        assertEquals(FetchStatus.FULLTEXT_BLOCKED, result.status());
        assertNull(result.body());
        server.verify();
    }

    /**
     * 200인데 본문이 없는 경우도 차단이다. 페이월이 로그인 안내만 주는 흔한 형태다.
     */
    @Test
    void reportsBlockedWhenPageHasNoBody() {
        server.expect(requestTo(ARTICLE_URL))
                .andRespond(withSuccess("<html><body><p>로그인 후 이용해 주세요.</p></body></html>",
                        MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT_BLOCKED, client.fetch(ARTICLE_URL, null).status());
    }

    @Test
    void reportsFailureForServerError() {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
    }

    @Test
    void retriesServerErrorThenSucceeds() {
        ArticleContentClient retrying =
                new ArticleContentClient(builder, rateLimiter, "external-news-agent", 3, 0L, 0L);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, retrying.fetch(ARTICLE_URL, null).status());
        server.verify();
    }

    /**
     * 404를 재시도하면 실패를 세 배 느리게 알게 될 뿐이다.
     */
    @Test
    void doesNotRetryClientError() {
        ArticleContentClient retrying =
                new ArticleContentClient(builder, rateLimiter, "external-news-agent", 3, 0L, 0L);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(FetchStatus.FETCH_FAILED, retrying.fetch(ARTICLE_URL, null).status());
        server.verify();
    }

    /**
     * charset 헤더 없이 meta 태그에만 적어 두는 매체가 있다. 문자열로 먼저 디코드하면 ISO-8859-1로 읽혀
     * 한글이 깨진다. 바이트로 받아 Jsoup이 판별하게 해야 한다.
     */
    @Test
    void readsKoreanWhenResponseHasNoCharsetHeader() {
        String html = """
                <html><head><meta charset="utf-8"></head>
                <body><article><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);
        server.expect(requestTo(ARTICLE_URL))
                .andRespond(withSuccess(html.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null);

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertTrue(result.body().contains("HBM4 양산 일정"));
    }
}
