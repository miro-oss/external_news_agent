package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ResponseCloseProbe;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsRules;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.Duration;

import static org.mockito.Mockito.*;

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
    private final RobotsTxtClient robots = mock(RobotsTxtClient.class);
    private final ArticleContentClient client =
            new ArticleContentClient(builder, rateLimiter, robots, "external-news-agent", 1, 0L, 0L);

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/private", "https://169.254.169.254/metadata",
            "https://metadata.google.internal/a", "https://[::1]/a", "http://10.0.0.1/a"})
    void blocksPrivateInitialUrlsEvenWhenRobotsAreIgnored(String url) {
        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(url, null, null, false).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/private", "https://169.254.169.254/metadata",
            "https://metadata.google.internal/a", "https://[::1]/a", "https://10.0.0.1/a"})
    void rejectsPrivateRedirectBeforeEvenCheckingItsRobots(String target) {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @Test
    void robotsIgnoreDoesNotPermitPrivateRedirects() {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://127.0.0.1/private"));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null, null, false).status());
        verifyNoInteractions(robots);
        server.verify();
    }

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

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsRelativeRedirectOnlyAfterCheckingTheNewPath(int status) {
        String target = "https://www.hankyung.com/article/final?edition=1";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched(
                "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.valueOf(status))
                .header(HttpHeaders.LOCATION, "final?edition=1#story"));
        server.expect(requestTo(target))
                .andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, client.fetch(ARTICLE_URL, null).status());
        verify(robots).lookup(target);
        verifyNoMoreInteractions(robots);
        server.verify();
    }

    @Test
    void neverFetchesARedirectedPathDisallowedByRobotsEvenOnTheSameHost() {
        String target = "https://www.hankyung.com/private/article";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched(
                "https://www.hankyung.com/robots.txt",
                RobotsRules.parse("User-agent: *\nDisallow: /private/", "external-news-agent")));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/private/article"));

        assertEquals(FetchStatus.ROBOTS_DISALLOWED, client.fetch(ARTICLE_URL, null).status());
        server.verify();
    }

    @Test
    void preservesEncodedPathAndQueryInTheResolvedLocation() {
        String target = "https://www.hankyung.com/article/%ED%95%9C%EA%B8%80?next=%2Fstory%3Fx%3D1";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched(
                "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target));
        server.expect(requestTo(target)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, client.fetch(ARTICLE_URL, null).status());
        server.verify();
    }

    @Test
    void reservesEveryHopAndUsesTheNewHostsCrawlDelayBeforeRequestingItsArticle() {
        DomainRateLimiter limiter = mock(DomainRateLimiter.class);
        ArticleContentClient redirected = new ArticleContentClient(builder, limiter, robots,
                "external-news-agent", 1, 0L, 0L);
        String target = "https://publisher.example/story";
        String finalTarget = "https://publisher.example/final";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched("https://publisher.example/robots.txt",
                RobotsRules.parse("User-agent: *\nCrawl-delay: 7", "external-news-agent")));
        when(robots.lookup(finalTarget)).thenReturn(RobotsLookup.fetched("https://publisher.example/robots.txt",
                RobotsRules.parse("User-agent: *\nCrawl-delay: 9", "external-news-agent")));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, target));
        server.expect(requestTo(target)).andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT)
                .header(HttpHeaders.LOCATION, "/final"));
        server.expect(requestTo(finalTarget)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, redirected.fetch(ARTICLE_URL, Duration.ofSeconds(2)).status());
        var order = inOrder(limiter, robots);
        order.verify(limiter).await(ARTICLE_URL, Duration.ofSeconds(2));
        order.verify(limiter).await(target, null);
        order.verify(robots).lookup(target);
        order.verify(limiter).await(target, Duration.ofSeconds(7));
        order.verify(limiter).await(finalTarget, null);
        order.verify(robots).lookup(finalTarget);
        order.verify(limiter).await(finalTarget, Duration.ofSeconds(9));
        order.verifyNoMoreInteractions();
        server.verify();
    }

    @Test
    void anExplicitIgnorePolicySkipsRobotsAcrossAllRedirectsButKeepsDomainRateLimiting() {
        DomainRateLimiter limiter = mock(DomainRateLimiter.class);
        ArticleContentClient ignoring = new ArticleContentClient(builder, limiter, robots,
                "external-news-agent", 1, 0L, 0L);
        String target = "https://publisher.example/story";
        String finalTarget = "https://publisher.example/final";
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target));
        server.expect(requestTo(target)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/final"));
        server.expect(requestTo(finalTarget)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, ignoring.fetch(ARTICLE_URL, Duration.ofSeconds(9), null, false).status());
        verifyNoInteractions(robots);
        var order = inOrder(limiter);
        order.verify(limiter).await(ARTICLE_URL, null);
        order.verify(limiter).await(target, null);
        order.verify(limiter).await(finalTarget, null);
        order.verifyNoMoreInteractions();
        server.verify();
    }

    @Test
    void rejectsRedirectLoopsWithoutRequestingTheOriginalArticleAgain() {
        String target = "https://www.hankyung.com/article/next";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched(
                "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "next"));
        server.expect(requestTo(target)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "../article/1#again"));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
        verify(robots).lookup(target);
        verifyNoMoreInteractions(robots);
        server.verify();
    }

    @Test
    void rejectsRedirectWithoutLocationAndDoesNotExtractItsResponseBody() {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .body(HTML).contentType(MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @Test
    void permitsFiveRedirectsButNeverRequestsASixthTarget() {
        for (int hop = 1; hop <= 6; hop++) {
            String url = "https://www.hankyung.com/article/" + hop;
            if (hop > 1) {
                when(robots.lookup(url)).thenReturn(RobotsLookup.fetched(
                        "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
            }
            server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, String.valueOf(hop + 1)));
        }

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
        verify(robots, times(5)).lookup(anyString());
        server.verify();
    }

    @Test
    void acceptsAnArticleAtTheFifthRedirectTarget() {
        for (int hop = 1; hop <= 6; hop++) {
            String url = "https://www.hankyung.com/article/" + hop;
            if (hop > 1) {
                when(robots.lookup(url)).thenReturn(RobotsLookup.fetched(
                        "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
            }
            server.expect(requestTo(url)).andRespond(hop == 6 ? withSuccess(HTML, MediaType.TEXT_HTML)
                    : withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, String.valueOf(hop + 1)));
        }

        assertEquals(FetchStatus.FULLTEXT, client.fetch(ARTICLE_URL, null).status());
        verify(robots, times(5)).lookup(anyString());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///private/data", "ftp://publisher.example/story", "http://publisher.example/story",
            "https://user:password@publisher.example/story", "https:///missing-host", "https://publisher.example:70000/story", "bad location"})
    void rejectsInvalidRedirectsAndHttpsDowngradesBeforeRobotsOrArticleRequests(String location) {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @Test
    void retriesTheCurrentRedirectTargetWithoutRestartingTheChain() {
        String target = "https://www.hankyung.com/article/final";
        when(robots.lookup(target)).thenReturn(RobotsLookup.fetched(
                "https://www.hankyung.com/robots.txt", RobotsRules.permitAll()));
        var retrying = new ArticleContentClient(builder, rateLimiter, robots, "external-news-agent", 3, 0L, 0L);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "final"));
        server.expect(requestTo(target)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(target)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, retrying.fetch(ARTICLE_URL, null).status());
        verify(robots).lookup(target);
        server.verify();
    }

    @Test
    void preservesTheExistingUnknownRobotsPolicyForRedirectTargets() {
        String target = "https://www.hankyung.com/article/final";
        when(robots.lookup(target)).thenReturn(RobotsLookup.unknown(
                "https://www.hankyung.com/robots.txt", "HTTP_503"));
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "final"));
        server.expect(requestTo(target)).andRespond(withSuccess(HTML, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FULLTEXT, client.fetch(ARTICLE_URL, null).status());
        server.verify();
    }

    @Test
    void acceptsAShortExplicitBodyParagraphEvenWhenItRepeatsTheFeedTitle() {
        String bulletin = "한국은행이 기준금리를 동결했다.";
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(bulletin);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, bulletin);

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertEquals(bulletin, result.body());
        server.verify();
    }

    @Test
    void usesFeedTitleContextToRejectACopiedHeadlineWithoutAParagraph() {
        String title = "한국은행이 기준금리를 동결했다.";
        String html = "<div id='articleBody'><span>%s</span></div>".formatted(title);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, title);

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "The central bank held interest rates steady|The central bank held interest rates steady.",
            "THE CENTRAL BANK HELD INTEREST RATES STEADY!|The central bank held interest rates steady.",
            "한국은행이 기준금리를 동결했다|한국은행이 기준금리를 동결했다."})
    void rejectsCopiedFeedTitleDespiteCaseOrTerminalPunctuationDifferences(String title, String copiedTitle) {
        String html = "<div id='articleBody'><span>%s</span></div>".formatted(copiedTitle);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, title);

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
        server.verify();
    }

    @Test
    void acceptsRealParagraphDespiteCaseAndPunctuationDifferencesFromFeedTitle() {
        String paragraph = "The central bank held interest rates steady.";
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(paragraph);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, "THE CENTRAL BANK HELD INTEREST RATES STEADY!");

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertEquals(paragraph, result.body());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Photo: A semiconductor plant stands beside the river.",
            "Image: Engineers examine a newly built chip factory.",
            "Caption: The central bank building is shown on Monday.",
            "Photo by Jane Doe."})
    void successfulHttpResponseWithOnlyAnEnglishCaptionIsNotFullText(String caption) {
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(caption);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, "Semiconductor industry news");

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
        server.verify();
    }

    @Test
    void copiedFeedTitleAndCaptionParagraphTogetherStillFailBodyExtraction() {
        String html = "<div id='articleBody'><span>The central bank held interest rates steady.</span>"
                + "<p>Photo: Central bank building.</p></div>";
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, "THE CENTRAL BANK HELD INTEREST RATES STEADY");

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
        server.verify();
    }

    @Test
    void acceptsActualReportingAlongsideAnEnglishCaptionAndPreservesBoth() {
        String caption = "Photo: A semiconductor plant stands beside the river.";
        String bulletin = "The chipmaker opened the plant on Monday.";
        String html = "<div id='articleBody'><p>%s</p><p>%s</p></div>".formatted(caption, bulletin);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, "Chipmaker opens a new plant");

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertEquals(caption + "\n\n" + bulletin, result.body());
        server.verify();
    }

    @Test
    void aSuccessfulResponseWithAnExplicitSubscriptionNoticeIsStillAFetchFailure() {
        String html = "<div itemprop='articleBody'><p>Subscribe to read the full article.</p></div>";
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null, "다른 기사 제목");

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
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
     * 200인데 본문을 못 뽑은 건 "막혔다"가 아니라 "못 읽었다"이다. 차단으로 적으면 짧은 정상 기사가
     * 페이월 경고를 만든다.
     */
    @Test
    void reportsFailureWhenPageHasNoExtractableBody() {
        server.expect(requestTo(ARTICLE_URL))
                .andRespond(withSuccess("<html><body><p>로그인 후 이용해 주세요.</p></body></html>",
                        MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
    }

    @Test
    void reportsFailureWhenSuccessfulPageContainsOnlyPublisherFooter() {
        String notice = "Copyright © SYNTHETIC TEST PUBLISHER. All rights reserved. ".repeat(3);
        String html = """
                <html><body><div><p>%s</p><p>%s</p></div></body></html>
                """.formatted(notice, notice);
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(html, MediaType.TEXT_HTML));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null);

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertNull(result.body());
        server.verify();
    }

    /**
     * 짧은 기사도 정상 응답이다. 이걸 FULLTEXT_BLOCKED로 적으면 실행 상세에 없는 페이월이 보고된다.
     */
    @Test
    void doesNotCallShortArticleAPaywall() {
        server.expect(requestTo(ARTICLE_URL))
                .andRespond(withSuccess(
                        "<html><body><article><p>속보. 삼성전자 HBM4 양산.</p></article></body></html>",
                        MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
    }

    /**
     * charset이 헤더에만 있고 meta에는 없는 매체가 있다. 헤더를 버리면 본문이 깨진다.
     */
    @Test
    void usesCharsetFromContentTypeHeader() {
        String html = """
                <html><body><article><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);
        server.expect(requestTo(ARTICLE_URL))
                .andRespond(withSuccess(html.getBytes(Charset.forName("EUC-KR")),
                        MediaType.valueOf("text/html;charset=EUC-KR")));

        ArticleContentResult result = client.fetch(ARTICLE_URL, null);

        assertEquals(FetchStatus.FULLTEXT, result.status());
        assertTrue(result.body().contains("HBM4 양산 일정"));
    }

    /**
     * 상한을 넘는 응답은 다 받아 놓고 재는 게 아니라 상한까지만 읽고 버린다.
     */
    @Test
    void rejectsOversizedBody() {
        byte[] huge = new byte[3 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'a');
        server.expect(requestTo(ARTICLE_URL)).andRespond(withSuccess(huge, MediaType.TEXT_HTML));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
    }

    @Test
    void reportsFailureForServerError() {
        server.expect(requestTo(ARTICLE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(ARTICLE_URL, null).status());
    }

    @Test
    void retriesServerErrorThenSucceeds() {
        ArticleContentClient retrying =
                new ArticleContentClient(builder, rateLimiter, robots, "external-news-agent", 3, 0L, 0L);
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
                new ArticleContentClient(builder, rateLimiter, robots, "external-news-agent", 3, 0L, 0L);
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

    /**
     * ★ #35 리뷰 P1. <b>어느 경로로 빠져나가든 응답은 닫혀야 한다.</b> 여기는 피드와 달리
     * <b>기사 수만큼</b> 도는 자리라 닫지 않으면 커넥션 풀이 훨씬 빨리 마른다.
     *
     * <p>차단(401·403)·에러·{@code Content-Length} 초과는 본문을 읽지도 않고 빠져나가는 경로라
     * 스트림이 소진되며 저절로 닫히는 일도 없다. 위쪽 테스트들이 쓰는 {@code MockRestServiceServer}로는
     * 이걸 못 본다 — 응답이 메모리에 있어 닫든 말든 결과가 같다.
     */
    @Test
    void closesResponseOnEveryPath() {
        assertClosesResponse(ResponseCloseProbe.responding(
                HttpStatus.OK, MediaType.TEXT_HTML, HTML.getBytes(StandardCharsets.UTF_8)));
        assertClosesResponse(ResponseCloseProbe.responding(HttpStatus.FORBIDDEN));
        assertClosesResponse(ResponseCloseProbe.responding(HttpStatus.NOT_FOUND));
        assertClosesResponse(ResponseCloseProbe.responding(HttpStatus.FOUND));
        assertClosesResponse(ResponseCloseProbe.responding(HttpStatus.FOUND)
                .withHeader(HttpHeaders.LOCATION, ARTICLE_URL + "#same-resource"));
        assertClosesResponse(ResponseCloseProbe.responding(
                        HttpStatus.OK, MediaType.TEXT_HTML, HTML.getBytes(StandardCharsets.UTF_8))
                .withHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(64L * 1024 * 1024)));
    }

    /**
     * 재시도는 부를 때마다 새 응답을 받는다. 마지막 것만 닫으면 앞의 것들이 그대로 샌다.
     */
    @Test
    void closesEveryResponseAcrossRetries() {
        ResponseCloseProbe probe = ResponseCloseProbe.responding(HttpStatus.SERVICE_UNAVAILABLE);

        new ArticleContentClient(RestClient.builder().requestFactory(probe), rateLimiter, robots,
                "external-news-agent", 3, 0L, 0L).fetch(ARTICLE_URL, null);

        assertEquals(3, probe.created(), "재시도가 돌지 않았다");
        assertEquals(probe.created(), probe.closed(), "닫지 않고 흘린 응답이 있다");
    }

    private void assertClosesResponse(ResponseCloseProbe probe) {
        new ArticleContentClient(RestClient.builder().requestFactory(probe), rateLimiter, robots,
                "external-news-agent", 1, 0L, 0L).fetch(ARTICLE_URL, null);

        assertEquals(1, probe.created(), probe + "을 부르지 않았다");
        assertEquals(probe.created(), probe.closed(), probe + "을 닫지 않았다");
    }
    @Test
    void obtainsAnExplicitPublicResourceOnlyAfterCheckingItsOwnRobotsAndCrawlDelay() {
        DomainRateLimiter limiter = mock(DomainRateLimiter.class);
        ArticleContentClient resourceClient = new ArticleContentClient(builder, limiter, robots,
                "external-news-agent", 3, 0L, 0L);
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.parse(
                        "User-agent: *\nCrawl-delay: 3", "external-news-agent")));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        server.expect(requestTo(PublicArticleResourceTest.RESOURCE))
                .andExpect(header(HttpHeaders.USER_AGENT, "external-news-agent"))
                .andRespond(withSuccess(PublicArticleResourceTest.json(), MediaType.APPLICATION_JSON));

        assertEquals(FetchStatus.FULLTEXT, resourceClient.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        var order = inOrder(limiter, robots);
        order.verify(limiter).await(PublicArticleResourceTest.ARTICLE, null);
        order.verify(limiter).await(PublicArticleResourceTest.RESOURCE, null);
        order.verify(robots).lookup(PublicArticleResourceTest.RESOURCE);
        order.verify(limiter).await(PublicArticleResourceTest.RESOURCE, Duration.ofSeconds(3));
        order.verifyNoMoreInteractions();
        server.verify();
    }

    @Test
    void neverRequestsAResourceExplicitlyDisallowedByRobots() {
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.parse(
                        "User-agent: *\nDisallow: /data/", "external-news-agent")));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        assertEquals(FetchStatus.ROBOTS_DISALLOWED, client.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_401", "HTTP_403", "HTTP_451"})
    void stopsTheResourceFallbackWhenItsRobotsEndpointExplicitlyDeniesAccess(String reason) {
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.unknown(
                "https://publisher.example/robots.txt", reason));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        server.verify();
    }

    @Test
    void anExplicitIgnorePolicySkipsResourceRobotsButStillRequestsOnlyTheProvenResource() {
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        server.expect(requestTo(PublicArticleResourceTest.RESOURCE))
                .andRespond(withSuccess(PublicArticleResourceTest.json(), MediaType.APPLICATION_JSON));
        assertEquals(FetchStatus.FULLTEXT, client.fetch(PublicArticleResourceTest.ARTICLE, null, null, false).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 401, 403, 451, 429, 500})
    void resourceRedirectsDenialsAndErrorsDoNotStartAnotherRequestOrRetry(int status) {
        ArticleContentClient resourceClient = new ArticleContentClient(builder, rateLimiter, robots,
                "external-news-agent", 3, 0L, 0L);
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        server.expect(requestTo(PublicArticleResourceTest.RESOURCE)).andRespond(withStatus(HttpStatus.valueOf(status))
                .header(HttpHeaders.LOCATION, "https://other.example/never-fetch"));
        assertEquals(FetchStatus.FETCH_FAILED, resourceClient.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        server.verify();
    }

    @Test
    void existingHtmlBodyDoesNotRequestTheAdditionalResource() {
        String page = PublicArticleResourceTest.page().replace("<div id=\"view_content_body\"></div>",
                "<div id=\"view_content_body\"><p>" + PublicArticleResourceTest.BODY + "</p></div>");
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE)).andRespond(withSuccess(page, MediaType.TEXT_HTML));
        assertEquals(FetchStatus.FULLTEXT, client.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        verifyNoInteractions(robots);
        server.verify();
    }

    @Test
    void resourceMustBeJsonAndRemainWithinTheArticleSizeLimit() {
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        server.expect(requestTo(PublicArticleResourceTest.RESOURCE))
                .andRespond(withSuccess(PublicArticleResourceTest.json(), MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(2 * 1024 * 1024 + 1)));
        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        server.verify();
    }

    @Test
    void resourceWithHtmlContentTypeDoesNotBecomeAnArticleBody() {
        when(robots.lookup(PublicArticleResourceTest.RESOURCE)).thenReturn(RobotsLookup.fetched(
                "https://publisher.example/robots.txt", RobotsRules.permitAll()));
        server.expect(requestTo(PublicArticleResourceTest.ARTICLE))
                .andRespond(withSuccess(PublicArticleResourceTest.page(), MediaType.TEXT_HTML));
        server.expect(requestTo(PublicArticleResourceTest.RESOURCE))
                .andRespond(withSuccess(PublicArticleResourceTest.json(), MediaType.TEXT_HTML));
        assertEquals(FetchStatus.FETCH_FAILED, client.fetch(PublicArticleResourceTest.ARTICLE, null).status());
        server.verify();
    }

}
