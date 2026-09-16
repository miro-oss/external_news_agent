package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ResponseCloseProbe;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsRules;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.global.config.PublicDestinationPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ArticleContentClientSecondaryRetryTest {
    private static final Duration CRAWL_DELAY = Duration.ofSeconds(3);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DomainRateLimiter limiter = mock(DomainRateLimiter.class);
    private final RobotsTxtClient robots = mock(RobotsTxtClient.class);
    private final ArticleContentClient client = new ArticleContentClient(builder, limiter, robots,
            "external-news-agent", 3, 0L, 0L);

    enum ResourceKind {
        AMP(PublicArticleAlternativeTest.ARTICLE, PublicArticleAlternativeTest.ALTERNATIVE,
                PublicArticleAlternativeTest.page(), PublicArticleAlternativeTest.ampPage(), MediaType.TEXT_HTML),
        JSON(PublicArticleResourceTest.ARTICLE, PublicArticleResourceTest.RESOURCE,
                PublicArticleResourceTest.page(), PublicArticleResourceTest.json(), MediaType.APPLICATION_JSON);

        final String article;
        final String uri;
        final String page;
        final String body;
        final MediaType type;

        ResourceKind(String article, String uri, String page, String body, MediaType type) {
            this.article = article;
            this.uri = uri;
            this.page = page;
            this.body = body;
            this.type = type;
        }
    }

    static Stream<Arguments> transientStatuses() {
        return Arrays.stream(ResourceKind.values()).flatMap(kind -> Stream.of(429, 503)
                .map(status -> Arguments.of(kind, status)));
    }

    static Stream<Arguments> transientExceptions() {
        return Arrays.stream(ResourceKind.values()).flatMap(kind -> Stream.of(
                Arguments.of(kind, new SocketTimeoutException("timeout"), TIMEOUT),
                Arguments.of(kind, new IOException("connection reset"), NETWORK_IO)));
    }

    static Stream<Arguments> permanentExceptions() {
        var policy = assertThrows(PublicDestinationPolicy.RejectedDestinationException.class,
                () -> PublicDestinationPolicy.validate(URI.create("https://127.0.0.1/")));
        return Arrays.stream(ResourceKind.values()).flatMap(kind -> Stream.of(
                Arguments.of(kind, new SSLHandshakeException("certificate failure"), TLS_VALIDATION),
                Arguments.of(kind, new IOException("transport failure", policy), OUTBOUND_POLICY),
                Arguments.of(kind, new IllegalStateException("unexpected parser state"), UNEXPECTED_ERROR)));
    }

    @ParameterizedTest
    @MethodSource("transientStatuses")
    void retriesOnlyTheSameResourceAndReservesEachRequestSlot(ResourceKind kind, int status) {
        prepare(kind);
        expectFailure(kind, status);
        expectSuccess(kind);

        assertEquals(FetchStatus.FULLTEXT, client.fetch(kind.article, null).status());

        var order = inOrder(limiter, robots);
        order.verify(limiter).await(kind.article, null);
        order.verify(limiter).await(kind.uri, null);
        order.verify(robots).lookup(kind.uri);
        order.verify(limiter, times(2)).await(kind.uri, CRAWL_DELAY);
        order.verifyNoMoreInteractions();
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("transientExceptions")
    void retriesTransientTransportErrors(ResourceKind kind, IOException failure, ArticleContentFailureReason reason) {
        prepare(kind);
        server.expect(requestTo(kind.uri)).andRespond(request -> { throw failure; });
        expectSuccess(kind);

        assertEquals(FetchStatus.FULLTEXT, client.fetch(kind.article, null).status());
        verify(limiter, times(2)).await(kind.uri, CRAWL_DELAY);
        verify(robots, times(1)).lookup(kind.uri);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("transientStatuses")
    void temporaryHttpErrorsStopAtTheConfiguredAttemptLimit(ResourceKind kind, int status) {
        prepare(kind);
        for (int i = 0; i < 3; i++) {
            expectFailure(kind, status);
        }
        assertEquals(status == 429 ? HTTP_RATE_LIMITED : HTTP_SERVER_ERROR,
                client.fetch(kind.article, null).reason());
        verify(limiter, times(3)).await(kind.uri, CRAWL_DELAY);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("transientExceptions")
    void transportErrorsStopAtTheConfiguredAttemptLimit(ResourceKind kind, IOException failure,
                                                        ArticleContentFailureReason reason) {
        prepare(kind);
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(kind.uri)).andRespond(request -> { throw failure; });
        }
        assertEquals(reason, client.fetch(kind.article, null).reason());
        verify(limiter, times(3)).await(kind.uri, CRAWL_DELAY);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("permanentExceptions")
    void permanentExceptionsDoNotRetry(ResourceKind kind, Exception failure, ArticleContentFailureReason reason) {
        prepare(kind);
        server.expect(requestTo(kind.uri)).andRespond(request -> {
            if (failure instanceof IOException io) {
                throw io;
            }
            throw (RuntimeException) failure;
        });
        assertEquals(reason, client.fetch(kind.article, null).reason());
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    void aRedirectAfterATransientFailureDoesNotStartAnotherChain(ResourceKind kind) {
        prepare(kind);
        expectFailure(kind, 503);
        expectFailure(kind, 302);
        assertEquals(REDIRECT_REJECTED, client.fetch(kind.article, null).reason());
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    void interruptionDuringRateLimitPreventsTheSecondaryGet(ResourceKind kind) {
        prepare(kind);
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; })
                .when(limiter).await(kind.uri, CRAWL_DELAY);
        try {
            assertEquals(INTERRUPTED, client.fetch(kind.article, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    void interruptedBackoffPreventsRetryAndPreservesTheFlag(ResourceKind kind) {
        prepare(kind);
        server.expect(requestTo(kind.uri)).andRespond(request -> {
            Thread.currentThread().interrupt();
            return withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(request);
        });
        try {
            assertEquals(INTERRUPTED, client.fetch(kind.article, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(limiter, times(1)).await(kind.uri, CRAWL_DELAY);
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    void interruptedIoStopsWithoutAnotherAttempt(ResourceKind kind) {
        prepare(kind);
        server.expect(requestTo(kind.uri)).andRespond(request -> { throw new InterruptedIOException("cancelled"); });
        try {
            assertEquals(INTERRUPTED, client.fetch(kind.article, null).reason());
            assertTrue(Thread.currentThread().isInterrupted());
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    void closesEveryFailedResponseBeforeTheNextAttempt(ResourceKind kind) {
        allow(kind);
        ResponseCloseProbe original = ResponseCloseProbe.responding(HttpStatus.OK,
                MediaType.TEXT_HTML, kind.page.getBytes(StandardCharsets.UTF_8));
        ResponseCloseProbe secondary = ResponseCloseProbe.responding(HttpStatus.SERVICE_UNAVAILABLE);
        RestClient.Builder probeBuilder = RestClient.builder().requestFactory((uri, method) -> {
            if (uri.toString().equals(kind.article)) {
                return original.createRequest(uri, method);
            }
            assertEquals(kind.uri, uri.toString());
            assertEquals(original.created(), original.closed());
            assertEquals(secondary.created(), secondary.closed());
            return secondary.createRequest(uri, method);
        });
        ArticleContentClient probeClient = new ArticleContentClient(probeBuilder, limiter, robots,
                "external-news-agent", 3, 0L, 0L);

        assertEquals(HTTP_SERVER_ERROR, probeClient.fetch(kind.article, null).reason());
        assertEquals(1, original.created());
        assertEquals(1, original.closed());
        assertEquals(3, secondary.created());
        assertEquals(3, secondary.closed());
    }

    private void prepare(ResourceKind kind) {
        allow(kind);
        server.expect(requestTo(kind.article)).andRespond(withSuccess(kind.page, MediaType.TEXT_HTML));
    }

    private void allow(ResourceKind kind) {
        when(robots.lookup(kind.uri)).thenReturn(RobotsLookup.fetched("https://publisher.example/robots.txt",
                RobotsRules.parse("User-agent: *\nCrawl-delay: 3", "external-news-agent")));
    }

    private void expectFailure(ResourceKind kind, int status) {
        server.expect(requestTo(kind.uri)).andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .header(HttpHeaders.LOCATION, "https://publisher.example/must-not-follow"));
    }

    private void expectSuccess(ResourceKind kind) {
        server.expect(requestTo(kind.uri)).andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(kind.body, kind.type));
    }
}
