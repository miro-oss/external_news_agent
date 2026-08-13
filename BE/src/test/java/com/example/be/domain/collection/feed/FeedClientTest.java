package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeedClientTest {

    private static final String FEED_URL = "https://www.hankyung.com/feed/economy";

    private static final String RSS = """
            <rss version="2.0"><channel><item>
              <title>HBM4 양산</title>
              <link>https://www.hankyung.com/article/1</link>
              <description>요약</description>
              <pubDate>Mon, 10 Aug 2026 09:00:00 +0900</pubDate>
            </item></channel></rss>
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    // 재시도 1회·지연 0으로 둬서 테스트가 실제로 잠들지 않게 한다.
    private final DomainRateLimiter rateLimiter = new DomainRateLimiter(0L, 0L);
    private final FeedClient feedClient =
            new FeedClient(builder, rateLimiter, "external-news-agent", 1, 0L, 0L);

    private FetchResult fetch() {
        return feedClient.fetch(request()).result();
    }

    private FeedRequest request() {
        return new FeedRequest(FEED_URL, "ko", null, null, null);
    }

    @Test
    void fetchesAndParsesFeed() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML));

        FetchResult result = fetch();

        assertTrue(result.success());
        assertEquals(1, result.articles().size());
        server.verify();
    }

    /**
     * 항목이 없는 정상 피드는 실패가 아니다. 이걸 실패로 적으면 조용한 소스가 매번 경고를 남긴다.
     */
    @Test
    void treatsEmptyFeedAsSuccess() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess("<rss version=\"2.0\"><channel><title>빈 피드</title></channel></rss>",
                        MediaType.APPLICATION_XML));

        FetchResult result = fetch();

        assertTrue(result.success());
        assertTrue(result.articles().isEmpty());
        server.verify();
    }

    /**
     * 시트 URL이 HTML이었던 경우가 있었다(#15). 실행을 죽이지 않고 빈 목록을 준다.
     */
    @Test
    void reportsFailureWhenFeedIsActuallyHtml() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess("<!DOCTYPE html><html><body>News</body></html>", MediaType.TEXT_HTML));

        FetchResult result = fetch();

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        server.verify();
    }

    @Test
    void reportsFailureWhenFeedIsGone() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        FetchResult result = fetch();

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        server.verify();
    }

    @Test
    void marksServerFailureAsRetryable() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        FetchResult result = fetch();

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_RATE_LIMITED, result.failureCode());
        server.verify();
    }

    /**
     * 304는 실패가 아니다. 바뀐 게 없다는 뜻이고 파싱도 하지 않는다.
     */
    @Test
    void treatsNotModifiedAsSuccessWithoutParsing() {
        server.expect(requestTo(FEED_URL))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"abc123\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        FeedFetch fetch = feedClient.fetch(
                new FeedRequest(FEED_URL, "ko", "\"abc123\"", null, null));

        assertTrue(fetch.notModified());
        assertTrue(fetch.result().success());
        assertTrue(fetch.result().articles().isEmpty());
        server.verify();
    }

    /**
     * 다음 조건부 GET에 쓸 검증자를 응답에서 받아 둔다. 이게 없으면 매번 전체를 다시 받는다.
     */
    @Test
    void keepsValidatorsFromResponse() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML)
                        .header(HttpHeaders.ETAG, "\"v2\"")
                        .header(HttpHeaders.LAST_MODIFIED, "Mon, 10 Aug 2026 09:00:00 GMT"));

        FeedFetch fetch = feedClient.fetch(request());

        assertEquals("\"v2\"", fetch.etag());
        assertEquals("Mon, 10 Aug 2026 09:00:00 GMT", fetch.lastModified());
    }

    @Test
    void sendsUserAgent() {
        server.expect(requestTo(FEED_URL))
                .andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML));

        fetch();

        server.verify();
    }

    /**
     * 429·5xx만 다시 부른다. 4xx는 몇 번을 불러도 같은 답이다.
     */
    @Test
    void retriesServerErrorThenSucceeds() {
        FeedClient retrying = new FeedClient(builder, rateLimiter, "external-news-agent", 3, 0L, 0L);
        server.expect(requestTo(FEED_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(FEED_URL)).andRespond(withSuccess(RSS, MediaType.APPLICATION_XML));

        FetchResult result = retrying.fetch(request()).result();

        assertTrue(result.success());
        assertEquals(1, result.articles().size());
        server.verify();
    }

    @Test
    void doesNotRetryClientError() {
        FeedClient retrying = new FeedClient(builder, rateLimiter, "external-news-agent", 3, 0L, 0L);
        server.expect(requestTo(FEED_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertFalse(retrying.fetch(request()).result().success());
        server.verify();
    }

    /**
     * ★ #32 C2. 남이 주는 응답이라 크기를 신뢰할 수 없다. 상한이 없으면 큰 피드 하나가
     * Free 컨테이너 메모리(2GB)에 그대로 얹힌다.
     *
     * <p>{@code Content-Length}가 없는 chunked 응답이 흔해서, 헤더만 보는 걸로는 부족하다.
     * 이 테스트가 그 경로다 — MockRestServiceServer는 길이를 붙이지 않는다.
     */
    @Test
    void rejectsFeedLargerThanTheLimitWithoutContentLength() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(oversizedFeed(), MediaType.APPLICATION_XML));

        FetchResult result = fetch();

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        // 파싱 실패와 구분한다 — 둘 다 FEED_UNREADABLE이라 코드만 보면 무엇이 걸렸는지 알 수 없다.
        assertEquals("피드가 너무 크다", result.failureMessage());
        server.verify();
    }

    /**
     * 길이를 알려주면 본문을 읽기 전에 끊는다.
     */
    @Test
    void rejectsFeedLargerThanTheLimitByContentLength() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML)
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(64L * 1024 * 1024)));

        FetchResult result = fetch();

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        assertEquals("피드가 너무 크다", result.failureMessage());
        server.verify();
    }

    /**
     * 상한 아래는 그대로 통과해야 한다. 경계를 잘못 잡으면 정상 피드가 통째로 막힌다.
     */
    @Test
    void acceptsFeedUnderTheLimit() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML));

        assertTrue(fetch().success());
        server.verify();
    }

    /** 상한(1MiB)을 확실히 넘기는 피드. 항목을 늘려 채운다. */
    private String oversizedFeed() {
        StringBuilder feed = new StringBuilder("<rss version=\"2.0\"><channel>");
        String item = """
                <item><title>HBM4 양산</title><link>https://www.hankyung.com/article/%d</link>
                <description>%s</description></item>
                """;
        String padding = "가".repeat(500);
        for (int i = 0; feed.length() < 1024 * 1024 + 4096; i++) {
            feed.append(item.formatted(i, padding));
        }
        return feed.append("</channel></rss>").toString();
    }
}
