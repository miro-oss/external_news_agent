package com.example.be.domain.collection.connector.provider;

import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.sources.entity.SearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SerpApiSearchConnectorTest {

    private static final String NEWS_JSON = """
            {
              "news_results": [
                {
                  "title": "TSMC raises capex",
                  "link": "https://www.digitimes.com/news/a20260810.html",
                  "snippet": "TSMC said <b>capex</b> will rise",
                  "date": "Mon, 10 Aug 2026 09:00:00 +0000",
                  "source": { "name": "Digitimes" }
                },
                {
                  "title": "매체명 없는 기사",
                  "link": "https://www.trendforce.com/news/2026/08/10/news.html",
                  "snippet": "source 필드가 없다",
                  "date": "Mon, 10 Aug 2026 10:00:00 +0000"
                }
              ]
            }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void exposesSerpApiProvider() {
        assertEquals(SearchProvider.SERPAPI, connector().provider());
    }

    @Test
    void callsGoogleNewsEngineWithApiKey() {
        expectNewsRequest("en");

        connector().search(new SearchQuery("TSMC", 5, "en"));

        server.verify();
    }

    @Test
    void fallsBackToKoreanWhenQueryHasNoLanguage() {
        expectNewsRequest("ko");

        connector().search(new SearchQuery("TSMC", 5, null));

        server.verify();
    }

    @Test
    void mapsSourceNameAndFallsBackToHost() {
        expectNewsRequest("en");

        List<CollectedArticle> articles = connector().search(new SearchQuery("TSMC", 5, "en")).articles();

        assertEquals(2, articles.size());
        assertEquals("TSMC said capex will rise", articles.get(0).summary());
        assertEquals("Digitimes", articles.get(0).sourceName());
        assertEquals("www.trendforce.com", articles.get(1).sourceName());
    }

    /**
     * SerpAPI는 결과 수를 요청 파라미터로 받지 않아서 받은 뒤에 자른다.
     */
    @Test
    void trimsResultsToBatchSize() {
        expectNewsRequest("en");

        assertEquals(1, connector().search(new SearchQuery("TSMC", 1, "en")).articles().size());
    }

    @Test
    void reportsMissingKeyAndSkipsHttp() {
        SerpApiSearchConnector connector = new SerpApiSearchConnector(builder, "");

        FetchResult result = connector.search(new SearchQuery("TSMC", 5, "en"));

        assertFalse(result.success());
        assertEquals(CollectionRunWarning.CODE_PROVIDER_KEY_MISSING, result.failureCode());
        server.verify();
    }

    private SerpApiSearchConnector connector() {
        return new SerpApiSearchConnector(builder, "test-key");
    }

    private void expectNewsRequest(String language) {
        server.expect(requestTo("https://serpapi.com/search.json"
                        + "?engine=google_news&q=TSMC&hl=" + language + "&api_key=test-key"))
                .andRespond(withSuccess(NEWS_JSON, MediaType.APPLICATION_JSON));
    }
}
