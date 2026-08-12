package com.example.be.domain.collection.connector.provider;

import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.sources.entity.SearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TavilySearchConnectorTest {

    private static final String SEARCH_URI = "https://api.tavily.com/search";

    private static final String SEARCH_JSON = """
            {
              "query": "HBM4",
              "results": [
                {
                  "title": "SK hynix ships <b>HBM4</b>",
                  "url": "https://www.eetimes.com/sk-hynix-ships-hbm4/",
                  "content": "SK hynix started shipping HBM4 samples &amp; more",
                  "score": 0.91,
                  "published_date": "Mon, 10 Aug 2026 09:00:00 +0000"
                },
                {
                  "title": "URL 없는 결과",
                  "url": "",
                  "content": "저장할 원문 URL이 없다",
                  "published_date": "Mon, 10 Aug 2026 10:00:00 +0000"
                }
              ]
            }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void exposesTavilyProvider() {
        assertEquals(SearchProvider.TAVILY, connector().provider());
    }

    @Test
    void postsBearerTokenAndNewsTopic() {
        server.expect(requestTo(SEARCH_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.query").value("HBM4"))
                .andExpect(jsonPath("$.topic").value("news"))
                .andExpect(jsonPath("$.max_results").value(5))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        connector().search(new SearchQuery("HBM4", 5, "en"));

        server.verify();
    }

    /**
     * 주제의 batchSize는 100까지 올라갈 수 있지만 Tavily는 그만큼 돌려주지 않는다. 그대로 넘기면 400이다.
     */
    @Test
    void capsMaxResultsAtTavilyLimit() {
        server.expect(requestTo(SEARCH_URI))
                .andExpect(jsonPath("$.max_results").value(20))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        connector().search(new SearchQuery("HBM4", 100, "en"));

        server.verify();
    }

    @Test
    void mapsResultsAndDropsOnesWithoutUrl() {
        server.expect(requestTo(SEARCH_URI))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        List<CollectedArticle> articles = connector().search(new SearchQuery("HBM4", 5, "en"));

        assertEquals(1, articles.size());
        CollectedArticle article = articles.get(0);
        assertEquals("SK hynix ships HBM4", article.title());
        assertEquals("https://www.eetimes.com/sk-hynix-ships-hbm4/", article.canonicalUrl());
        assertEquals("SK hynix started shipping HBM4 samples & more", article.summary());
        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.UTC), article.publishedAt());
        assertEquals("www.eetimes.com", article.sourceName());
        assertEquals("en", article.language());
    }

    /**
     * 주제에 언어가 없으면 language가 null로 새어 나간다. M3가 이 값을 그대로 저장한다.
     */
    @Test
    void fallsBackToEnglishWhenQueryHasNoLanguage() {
        server.expect(requestTo(SEARCH_URI))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        CollectedArticle article = connector().search(new SearchQuery("HBM4", 5, null)).get(0);

        assertEquals("en", article.language());
    }

    @Test
    void returnsEmptyAndSkipsHttpWithoutApiKey() {
        TavilySearchConnector connector = new TavilySearchConnector(builder, "");

        assertTrue(connector.search(new SearchQuery("HBM4", 5, "en")).isEmpty());
        server.verify();
    }

    private TavilySearchConnector connector() {
        return new TavilySearchConnector(builder, "test-key");
    }
}
