package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Tavily 검색. 해외·일반 웹 보강용이다 (plan-final §2-6).
 *
 * <p>URL 하나로 표현되지 않는 provider의 대표 사례다. POST + JSON 바디에 Bearer 인증을 쓴다.
 */
@Slf4j
@Component
public class TavilySearchConnector implements SearchConnector {

    private static final String BASE_URL = "https://api.tavily.com";
    private static final String SEARCH_PATH = "/search";

    private static final String NEWS_TOPIC = "news";
    private static final String BASIC_DEPTH = "basic";

    /** Tavily는 한 번에 돌려주는 결과 수에 상한이 있다. 주제의 batchSize가 더 커도 여기서 자른다. */
    private static final int MAX_RESULTS = 20;

    private final RestClient restClient;
    private final String apiKey;

    public TavilySearchConnector(RestClient.Builder restClientBuilder,
                                 @Value("${TAVILY_API_KEY:}") String apiKey) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    @Override
    public SearchProvider provider() {
        return SearchProvider.TAVILY;
    }

    @Override
    public List<CollectedArticle> search(SearchQuery query) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("TAVILY_API_KEY가 없어 Tavily 검색을 건너뛴다. queryText={}", query.queryText());
            return List.of();
        }

        try {
            SearchResponse response = restClient.post()
                    .uri(SEARCH_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "query", query.queryText(),
                            "topic", NEWS_TOPIC,
                            "search_depth", BASIC_DEPTH,
                            "max_results", Math.min(query.batchSize(), MAX_RESULTS)
                    ))
                    .retrieve()
                    .body(SearchResponse.class);

            return toArticles(response, query);
        } catch (RestClientException e) {
            return ConnectorSupport.emptyOnFailure(log, provider(), query, e);
        }
    }

    private List<CollectedArticle> toArticles(SearchResponse response, SearchQuery query) {
        if (response == null || response.results() == null) {
            return List.of();
        }

        return response.results().stream()
                .filter(result -> StringUtils.hasText(result.url()))
                .map(result -> toArticle(result, query))
                .toList();
    }

    private CollectedArticle toArticle(SearchResponse.Result result, SearchQuery query) {
        return new CollectedArticle(
                HtmlTextSanitizer.sanitize(result.title()),
                result.url(),
                HtmlTextSanitizer.sanitize(result.content()),
                ConnectorSupport.parsePublishedAt(log, result.publishedDate()),
                ConnectorSupport.hostOf(result.url()),
                query.language()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Result> results) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String title,
                      String url,
                      String content,
                      @JsonProperty("published_date") String publishedDate) {
        }
    }
}
