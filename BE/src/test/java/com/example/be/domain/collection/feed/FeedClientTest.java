package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final FeedClient feedClient = new FeedClient(builder);

    @Test
    void fetchesAndParsesFeed() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(RSS, MediaType.APPLICATION_XML));

        FetchResult result = feedClient.fetch(FEED_URL, "ko");

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

        FetchResult result = feedClient.fetch(FEED_URL, "ko");

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

        FetchResult result = feedClient.fetch(FEED_URL, "ko");

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        server.verify();
    }

    @Test
    void reportsFailureWhenFeedIsGone() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        FetchResult result = feedClient.fetch(FEED_URL, "ko");

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, result.failureCode());
        server.verify();
    }

    @Test
    void marksServerFailureAsRetryable() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        FetchResult result = feedClient.fetch(FEED_URL, "ko");

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_RATE_LIMITED, result.failureCode());
        server.verify();
    }
}
