package com.example.be.domain.collection.robots;

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

class RobotsTxtClientTest {

    private static final String FEED_URL = "https://www.hankyung.com/feed/economy";
    private static final String ROBOTS_URL = "https://www.hankyung.com/robots.txt";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RobotsTxtClient client = new RobotsTxtClient(builder, "external-news-agent");

    @Test
    void asksTheHostRootRegardlessOfFeedPath() {
        server.expect(requestTo(ROBOTS_URL))
                .andRespond(withSuccess("User-agent: *\nDisallow: /admin/\n", MediaType.TEXT_PLAIN));

        RobotsLookup lookup = client.lookup(FEED_URL);

        assertTrue(lookup.resolved());
        assertEquals(ROBOTS_URL, lookup.robotsTxtUrl());
        assertTrue(lookup.allows(FEED_URL));
        server.verify();
    }

    /**
     * robots.txt가 없는 사이트는 흔하다. 404는 "제한이 없다"는 뜻이지 금지가 아니다.
     */
    @Test
    void treatsMissingFileAsNoRestriction() {
        server.expect(requestTo(ROBOTS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        RobotsLookup lookup = client.lookup(FEED_URL);

        assertTrue(lookup.resolved());
        assertTrue(lookup.allows(FEED_URL));
    }

    /**
     * 5xx는 판단할 근거가 없다는 뜻이다. 명세도 조회 실패를 disallowed가 아니라 unknown으로 적는다.
     */
    @Test
    void reportsUnknownWhenLookupFails() {
        server.expect(requestTo(ROBOTS_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        RobotsLookup lookup = client.lookup(FEED_URL);

        assertFalse(lookup.resolved());
        assertEquals("HTTP_500", lookup.reason());
        // 근거가 없다고 수집을 막지는 않는다.
        assertTrue(lookup.allows(FEED_URL));
    }

    @Test
    void detectsDisallowedFeed() {
        server.expect(requestTo(ROBOTS_URL))
                .andRespond(withSuccess("User-agent: *\nDisallow: /feed/\n", MediaType.TEXT_PLAIN));

        assertFalse(client.lookup(FEED_URL).allows(FEED_URL));
    }

    @Test
    void reportsUnknownForUnusableUrl() {
        RobotsLookup lookup = client.lookup("not-a-url");

        assertFalse(lookup.resolved());
        assertEquals("INVALID_URL", lookup.reason());
        server.verify();
    }
}
