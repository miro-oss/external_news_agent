package com.example.be.domain.collection.feed;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(1, feedClient.fetch(FEED_URL, "ko").size());
        server.verify();
    }

    /**
     * 시트 URL이 HTML이었던 경우가 있었다(#15). 실행을 죽이지 않고 빈 목록을 준다.
     */
    @Test
    void returnsEmptyWhenFeedIsActuallyHtml() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess("<!DOCTYPE html><html><body>News</body></html>", MediaType.TEXT_HTML));

        assertTrue(feedClient.fetch(FEED_URL, "ko").isEmpty());
    }

    @Test
    void returnsEmptyWhenFeedIsGone() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(feedClient.fetch(FEED_URL, "ko").isEmpty());
    }

    @Test
    void returnsEmptyWhenServerFails() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertTrue(feedClient.fetch(FEED_URL, "ko").isEmpty());
    }
}
