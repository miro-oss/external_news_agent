package com.example.be.domain.collection.content;

import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsRules;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ArticleContentClientAlternativeTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DomainRateLimiter limiter = mock(DomainRateLimiter.class);
    private final RobotsTxtClient robots = mock(RobotsTxtClient.class);
    private final ArticleContentClient client = new ArticleContentClient(builder, limiter, robots,
            "external-news-agent", 3, 0L, 0L);

    @Test
    void explicitAmpBodyRequiresItsOwnRobotsAndRateLimitSlot() {
        allowAmp();
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(PublicArticleAlternativeTest.ampPage(), MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(PublicArticleAlternativeTest.ARTICLE, null);

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertEquals(NONE, result.reason());
        var order = inOrder(limiter, robots);
        order.verify(limiter).await(PublicArticleAlternativeTest.ARTICLE, null);
        order.verify(limiter).await(PublicArticleAlternativeTest.ALTERNATIVE, null);
        order.verify(robots).lookup(PublicArticleAlternativeTest.ALTERNATIVE);
        order.verify(limiter).await(PublicArticleAlternativeTest.ALTERNATIVE, Duration.ofSeconds(3));
        order.verifyNoMoreInteractions();
        server.verify();
    }

    @Test
    void robotsDisallowedAlternativeIsNeverRequested() {
        when(robots.lookup(PublicArticleAlternativeTest.ALTERNATIVE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.parse(
                        "User-agent: *\nDisallow: /amp/", "external-news-agent")));
        expectOriginal();
        assertEquals(ROBOTS_DISALLOWED, client.fetch(PublicArticleAlternativeTest.ARTICLE, null).reason());
        server.verify();
    }

    @Test
    void robotsAccessDenialDoesNotAuthorizeTheAlternative() {
        when(robots.lookup(PublicArticleAlternativeTest.ALTERNATIVE))
                .thenReturn(RobotsLookup.unknown("https://publisher.example/robots.txt", "HTTP_403"));
        expectOriginal();
        assertEquals(HTTP_ACCESS_DENIED, client.fetch(PublicArticleAlternativeTest.ARTICLE, null).reason());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"302, REDIRECT_REJECTED", "403, HTTP_ACCESS_DENIED", "404, HTTP_CLIENT_ERROR"})
    void permanentSecondaryFailureNeverStartsARetryOrRedirectChain(int status, ArticleContentFailureReason reason) {
        allowAmp();
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .header(HttpHeaders.LOCATION, "https://publisher.example/another"));
        ArticleContentResult result = client.fetch(PublicArticleAlternativeTest.ARTICLE, null);
        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertEquals(reason, result.reason());
        server.verify();
    }

    @Test
    void wrongArticleBodyIsNotStored() {
        allowAmp();
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andRespond(withSuccess(PublicArticleAlternativeTest.ampPage()
                        .replace(PublicArticleAlternativeTest.ARTICLE,
                                "https://publisher.example/article/999999"), MediaType.TEXT_HTML));
        assertEquals(RESOURCE_INVALID, client.fetch(PublicArticleAlternativeTest.ARTICLE, null).reason());
        server.verify();
    }

    @Test
    void secondaryBodyKeepsTheSameByteLimit() {
        allowAmp();
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andRespond(withSuccess(PublicArticleAlternativeTest.ampPage(), MediaType.TEXT_HTML)
                        .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(2 * 1024 * 1024 + 1)));
        assertEquals(BODY_TOO_LARGE, client.fetch(PublicArticleAlternativeTest.ARTICLE, null).reason());
        server.verify();
    }

    @Test
    void secondaryBodyMustBeHtml() {
        allowAmp();
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andRespond(withSuccess(PublicArticleAlternativeTest.ampPage(), MediaType.APPLICATION_JSON));
        assertEquals(UNSUPPORTED_CONTENT_TYPE, client.fetch(PublicArticleAlternativeTest.ARTICLE, null).reason());
        server.verify();
    }

    @Test
    void ignoreRobotsPolicyStillUsesOnlyTheExplicitAlternative() {
        expectOriginal();
        server.expect(requestTo(PublicArticleAlternativeTest.ALTERNATIVE))
                .andRespond(withSuccess(PublicArticleAlternativeTest.ampPage(), MediaType.TEXT_HTML));
        assertEquals(FetchStatus.FULLTEXT,
                client.fetch(PublicArticleAlternativeTest.ARTICLE, null, null, false).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    private void allowAmp() {
        when(robots.lookup(PublicArticleAlternativeTest.ALTERNATIVE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.parse(
                        "User-agent: *\nCrawl-delay: 3", "external-news-agent")));
    }

    private void expectOriginal() {
        server.expect(requestTo(PublicArticleAlternativeTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleAlternativeTest.page(), MediaType.TEXT_HTML));
    }
}
