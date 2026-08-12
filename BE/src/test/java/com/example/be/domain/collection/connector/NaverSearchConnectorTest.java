package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NaverSearchConnectorTest {

    private static final String NAVER_MIRROR = "https://n.news.naver.com/mnews/article/015/0000000001";

    private static final String NEWS_JSON = """
            {
              "lastBuildDate": "Wed, 12 Aug 2026 09:00:00 +0900",
              "total": 2,
              "start": 1,
              "display": 2,
              "items": [
                {
                  "title": "<b>삼성전자</b> HBM4 양산 &amp; 공급",
                  "originallink": "https://www.hankyung.com/article/2026081200001",
                  "link": "%s",
                  "description": "<b>삼성전자</b>가 HBM4를 &lt;b&gt;양산&lt;/b&gt;한다",
                  "pubDate": "Mon, 10 Aug 2026 09:00:00 +0900"
                },
                {
                  "title": "미러만 있는 기사",
                  "originallink": "",
                  "link": "https://n.news.naver.com/mnews/article/015/0000000002",
                  "description": "언론사 원문 URL이 없다",
                  "pubDate": "Mon, 10 Aug 2026 10:00:00 +0900"
                }
              ]
            }
            """.formatted(NAVER_MIRROR);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void exposesNaverProvider() {
        assertEquals(SearchProvider.NAVER, connector().provider());
    }

    /**
     * 이 커넥터의 핵심 계약이다. link는 네이버 뉴스 미러이고 originallink가 언론사 원문이다 (plan-final §2-6).
     */
    @Test
    void mapsOriginalLinkAndNeverTheNaverMirror() {
        expectNewsRequest();

        List<CollectedArticle> articles = connector().search(new SearchQuery("HBM", 5, "ko"));

        assertEquals(1, articles.size());
        assertEquals("https://www.hankyung.com/article/2026081200001", articles.get(0).canonicalUrl());
        assertTrue(articles.stream().noneMatch(article -> NAVER_MIRROR.equals(article.canonicalUrl())));
        server.verify();
    }

    @Test
    void stripsTagsBeforeDecodingEntities() {
        expectNewsRequest();

        CollectedArticle article = connector().search(new SearchQuery("HBM", 5, "ko")).get(0);

        assertEquals("삼성전자 HBM4 양산 & 공급", article.title());
        assertEquals("삼성전자가 HBM4를 <b>양산</b>한다", article.summary());
    }

    @Test
    void parsesRfc2822PublishedAt() {
        expectNewsRequest();

        CollectedArticle article = connector().search(new SearchQuery("HBM", 5, "ko")).get(0);

        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9)), article.publishedAt());
        assertEquals("www.hankyung.com", article.sourceName());
        assertEquals("ko", article.language());
    }

    @Test
    void leavesPublishedAtEmptyWhenPubDateIsUnreadable() {
        server.expect(requestTo(newsUri(5)))
                .andRespond(withSuccess("""
                        {
                          "items": [
                            {
                              "title": "발행일이 깨진 기사",
                              "originallink": "https://www.hankyung.com/article/2026081200002",
                              "link": "%s",
                              "description": "설명",
                              "pubDate": "2026년 8월 10일"
                            }
                          ]
                        }
                        """.formatted(NAVER_MIRROR), MediaType.APPLICATION_JSON));

        CollectedArticle article = connector().search(new SearchQuery("HBM", 5, "ko")).get(0);

        assertNull(article.publishedAt());
    }

    @Test
    void sendsCredentialsAndBatchSizeAsDisplay() {
        server.expect(requestTo(newsUri(7)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Naver-Client-Id", "test-id"))
                .andExpect(header("X-Naver-Client-Secret", "test-secret"))
                .andRespond(withSuccess(NEWS_JSON, MediaType.APPLICATION_JSON));

        connector().search(new SearchQuery("HBM", 7, "ko"));

        server.verify();
    }

    /**
     * 키가 없으면 예외를 던지지 않고 아예 호출하지 않는다. 새 팀원이 키 없이 bootRun 할 수 있어야 한다.
     */
    @Test
    void returnsEmptyAndSkipsHttpWithoutCredentials() {
        NaverSearchConnector connector = new NaverSearchConnector(builder, "", "");

        assertTrue(connector.search(new SearchQuery("HBM", 5, "ko")).isEmpty());
        server.verify();
    }

    @Test
    void returnsEmptyWhenNaverRejectsTheRequest() {
        server.expect(requestTo(newsUri(5)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertTrue(connector().search(new SearchQuery("HBM", 5, "ko")).isEmpty());
    }

    @Test
    void returnsEmptyWhenNaverIsDown() {
        server.expect(requestTo(newsUri(5)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertTrue(connector().search(new SearchQuery("HBM", 5, "ko")).isEmpty());
    }

    private NaverSearchConnector connector() {
        return new NaverSearchConnector(builder, "test-id", "test-secret");
    }

    private void expectNewsRequest() {
        server.expect(requestTo(newsUri(5)))
                .andRespond(withSuccess(NEWS_JSON, MediaType.APPLICATION_JSON));
    }

    private String newsUri(int display) {
        return "https://openapi.naver.com/v1/search/news.json?query=HBM&display=" + display + "&sort=date";
    }
}
