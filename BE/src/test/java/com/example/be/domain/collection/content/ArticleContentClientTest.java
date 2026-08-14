package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ResponseCloseProbe;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

        new ArticleContentClient(RestClient.builder().requestFactory(probe), rateLimiter,
                "external-news-agent", 3, 0L, 0L).fetch(ARTICLE_URL, null);

        assertEquals(3, probe.created(), "재시도가 돌지 않았다");
        assertEquals(probe.created(), probe.closed(), "닫지 않고 흘린 응답이 있다");
    }

    private void assertClosesResponse(ResponseCloseProbe probe) {
        new ArticleContentClient(RestClient.builder().requestFactory(probe), rateLimiter,
                "external-news-agent", 1, 0L, 0L).fetch(ARTICLE_URL, null);

        assertEquals(1, probe.created(), probe + "을 부르지 않았다");
        assertEquals(probe.created(), probe.closed(), probe + "을 닫지 않았다");
    }
}
