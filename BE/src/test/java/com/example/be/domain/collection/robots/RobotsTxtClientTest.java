package com.example.be.domain.collection.robots;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RobotsTxtClientTest {

    private static final int MAX_BODY_BYTES = 512 * 1024;

    private static final String FEED_URL = "https://www.hankyung.com/feed/economy";
    private static final String ROBOTS_URL = "https://www.hankyung.com/robots.txt";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RobotsTxtClient client = new RobotsTxtClient(builder, "external-news-agent");

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/private", "https://169.254.169.254/metadata",
            "https://metadata.google.internal/a", "https://[::1]/a", "http://10.0.0.1/a",
            "ftp://news.example/a", "https://user:pass@news.example/a"})
    void neverRequestsRobotsAtPrivateOrInvalidDestinations(String url) {
        assertEquals("INVALID_URL", client.lookup(url).reason());
        server.verify();
    }

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

    /**
     * 401·403은 "없다"가 아니라 "안 보여준다"이다. 제한 없음으로 읽으면 접근이 거부된 robots.txt를
     * 허용으로 오판한다.
     */
    @Test
    void reportsUnknownWhenLookupIsForbidden() {
        server.expect(requestTo(ROBOTS_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        RobotsLookup lookup = client.lookup(FEED_URL);

        assertFalse(lookup.resolved());
        assertEquals("HTTP_403", lookup.reason());
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

    @Test
    void stopsReadingAnUnknownLengthBodyAtTheByteLimit() {
        CountingBody body = new CountingBody(MAX_BODY_BYTES * 4);
        server.expect(requestTo(ROBOTS_URL)).andRespond(request -> new MockClientHttpResponse(body, HttpStatus.OK));

        assertEquals("TOO_LARGE", client.lookup(FEED_URL).reason());
        assertEquals(MAX_BODY_BYTES + 1, body.consumed);
        assertTrue(body.closed);
        server.verify();
    }

    @Test
    void rejectsDeclaredOversizeWithoutReadingTheBody() {
        CountingBody body = new CountingBody(MAX_BODY_BYTES * 4);
        server.expect(requestTo(ROBOTS_URL)).andRespond(request -> {
            var response = new MockClientHttpResponse(body, HttpStatus.OK);
            response.getHeaders().setContentLength(MAX_BODY_BYTES + 1);
            return response;
        });

        assertEquals("TOO_LARGE", client.lookup(FEED_URL).reason());
        assertEquals(0, body.consumed);
        assertTrue(body.closed);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 403, 404, 500})
    void neverBuffersErrorOrRedirectBodies(int status) {
        CountingBody body = new CountingBody(MAX_BODY_BYTES * 4);
        server.expect(requestTo(ROBOTS_URL)).andRespond(request ->
                new MockClientHttpResponse(body, HttpStatus.valueOf(status)));

        RobotsLookup lookup = client.lookup(FEED_URL);

        if (status == 404) assertTrue(lookup.resolved());
        else assertEquals("HTTP_" + status, lookup.reason());
        assertEquals(0, body.consumed);
        assertTrue(body.closed);
    }

    @Test
    void acceptsABodyExactlyAtTheLimit() {
        CountingBody body = new CountingBody(MAX_BODY_BYTES);
        server.expect(requestTo(ROBOTS_URL)).andRespond(request -> new MockClientHttpResponse(body, HttpStatus.OK));

        assertTrue(client.lookup(FEED_URL).resolved());
        assertEquals(MAX_BODY_BYTES, body.consumed);
    }

    private static final class CountingBody extends InputStream {
        private final int length;
        private int consumed;
        private boolean closed;

        private CountingBody(int length) {
            this.length = length;
        }

        @Override
        public int read() {
            if (consumed == length) return -1;
            consumed++;
            return 'a';
        }

        @Override
        public int read(byte[] bytes, int offset, int count) {
            if (count == 0) return 0;
            if (consumed == length) return -1;
            int actual = Math.min(count, length - consumed);
            Arrays.fill(bytes, offset, offset + actual, (byte) 'a');
            consumed += actual;
            return actual;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
