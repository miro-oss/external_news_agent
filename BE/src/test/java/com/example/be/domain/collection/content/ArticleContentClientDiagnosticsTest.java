package com.example.be.domain.collection.content;

import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.global.config.PublicDestinationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ArticleContentClientDiagnosticsTest {
    private static final String URL = "https://publisher.example/article/1";
    private static final String HTML = "<article><p>"
            + "삼성전자가 고객사와 반도체 공급 계약을 체결했다고 밝혔다. 공급 확대를 위해 생산 시설을 증설할 계획이다. ".repeat(6)
            + "</p></article>";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DomainRateLimiter limiter = mock(DomainRateLimiter.class);
    private final RobotsTxtClient robots = mock(RobotsTxtClient.class);
    private final ArticleContentClient client = new ArticleContentClient(builder, limiter, robots,
            "external-news-agent", 3, 0L, 0L);

    @ParameterizedTest
    @CsvSource({"401, HTTP_ACCESS_DENIED", "403, HTTP_ACCESS_DENIED", "451, HTTP_ACCESS_DENIED",
            "404, HTTP_CLIENT_ERROR", "302, REDIRECT_REJECTED", "200, BODY_NOT_FOUND"})
    void permanentHttpAndBodyFailuresStopAfterOneRequest(int status, ArticleContentFailureReason reason) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        ArticleContentResult result = client.fetch(URL, null);
        assertEquals(reason, result.reason());
        assertEquals(reason == HTTP_ACCESS_DENIED ? FetchStatus.FULLTEXT_BLOCKED : FetchStatus.FETCH_FAILED,
                result.status());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"429, HTTP_RATE_LIMITED", "500, HTTP_SERVER_ERROR", "503, HTTP_SERVER_ERROR"})
    void temporaryHttpFailuresExhaustOnlyTheConfiguredAttempts(int status, ArticleContentFailureReason reason) {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        }
        assertEquals(reason, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void tlsFailureStopsAfterOneAttempt() {
        server.expect(requestTo(URL)).andRespond(request -> { throw new SSLHandshakeException("certificate failure"); });
        assertEquals(TLS_VALIDATION, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void dnsWrappedPolicyFailureStopsAfterOneAttempt() {
        UnknownHostException failure = new UnknownHostException("policy rejected");
        failure.initCause(assertThrows(PublicDestinationPolicy.RejectedDestinationException.class,
                () -> PublicDestinationPolicy.validate(URI.create("https://127.0.0.1/"))));
        server.expect(requestTo(URL)).andRespond(request -> { throw failure; });
        assertEquals(OUTBOUND_POLICY, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void unexpectedProgrammingExceptionIsNotRetried() {
        server.expect(requestTo(URL)).andRespond(request -> { throw new IllegalStateException("invalid state"); });
        assertEquals(UNEXPECTED_ERROR, client.fetch(URL, null).reason());
        server.verify();
    }

    static Stream<IOException> transientFailures() {
        return Stream.of(new SocketTimeoutException("timeout"), new IOException("connection reset"));
    }

    @ParameterizedTest
    @MethodSource("transientFailures")
    void transientIoCanRecoverOnNextAttempt(IOException failure) {
        server.expect(requestTo(URL)).andRespond(request -> { throw failure; });
        server.expect(requestTo(URL)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));
        ArticleContentResult result = client.fetch(URL, null);
        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertEquals(NONE, result.reason());
        server.verify();
    }

    @Test
    void timeoutExhaustionRetainsItsSpecificReason() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(request -> { throw new SocketTimeoutException("timeout"); });
        }
        assertEquals(TIMEOUT, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void interruptionBeforeFetchDoesNotTouchAnyDependency() {
        try {
            Thread.currentThread().interrupt();
            assertEquals(INTERRUPTED, client.fetch(URL, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(limiter, robots);
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringRateLimitStopsBeforeNetwork() {
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; }).when(limiter).await(URL, null);
        try {
            assertEquals(INTERRUPTED, client.fetch(URL, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(robots);
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedBackoffDoesNotRetry() {
        server.expect(requestTo(URL)).andRespond(request -> {
            Thread.currentThread().interrupt();
            return withStatus(HttpStatusCode.valueOf(503)).createResponse(request);
        });
        try {
            assertEquals(INTERRUPTED, client.fetch(URL, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({"'not a URL', INVALID_URL", "'/article/1', INVALID_URL",
            "'https://127.0.0.1/a', OUTBOUND_POLICY"})
    void rejectedUrlsKeepTheFailureCategory(String url, ArticleContentFailureReason reason) {
        assertEquals(reason, client.fetch(url, null).reason());
        verifyNoInteractions(limiter, robots);
        server.verify();
    }

    @Test
    void aPrivateRedirectIsRejectedBeforeRobotsLookup() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(302))
                .header(HttpHeaders.LOCATION, "https://169.254.169.254/latest"));
        assertEquals(OUTBOUND_POLICY, client.fetch(URL, null).reason());
        verifyNoInteractions(robots);
        server.verify();
    }

    @Test
    void unsupportedMediaTypeIsNotParsedAsHtml() {
        server.expect(requestTo(URL)).andRespond(withSuccess(HTML, MediaType.APPLICATION_JSON));
        assertEquals(UNSUPPORTED_CONTENT_TYPE, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void malformedMediaTypeIsNotRetried() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(200)).body(HTML)
                .header(HttpHeaders.CONTENT_TYPE, "bad type"));
        assertEquals(UNSUPPORTED_CONTENT_TYPE, client.fetch(URL, null).reason());
        server.verify();
    }

    @Test
    void oversizedBodyIsNotRetried() {
        server.expect(requestTo(URL)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(2 * 1024 * 1024 + 1)));
        assertEquals(BODY_TOO_LARGE, client.fetch(URL, null).reason());
        server.verify();
    }
}
