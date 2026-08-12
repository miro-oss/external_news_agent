package com.example.be.domain.collection.connector.provider;

import com.example.be.domain.collection.connector.SearchConnector;
import com.example.be.domain.collection.connector.converter.CollectedArticleConverter;
import com.example.be.domain.collection.connector.converter.HtmlTextSanitizer;
import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.sources.entity.SearchProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * SerpAPI(Google News). <b>기본 수집 경로에서는 쓰지 않는다</b> — Google 결과 확인이 꼭 필요한 보강 검색 전용이고,
 * 무료 크레딧이 한정돼 있다 (plan-final §2-6, §9 리스크 표).
 *
 * <p>API 키가 쿼리스트링으로 들어가는 provider라, 키를 url_template에 적으면 시크릿이 DB로 새어 나간다.
 * 그래서 url_template에는 {@code SERPAPI}만 두고 실제 URL은 이 어댑터가 소유한다 (§3-3).
 */
@Slf4j
@Component
public class SerpApiSearchConnector implements SearchConnector {

    private static final String BASE_URL = "https://serpapi.com";
    private static final String SEARCH_PATH = "/search.json";

    private static final String GOOGLE_NEWS_ENGINE = "google_news";
    private static final String DEFAULT_LANGUAGE = "ko";

    private final RestClient restClient;
    private final String apiKey;

    public SerpApiSearchConnector(RestClient.Builder restClientBuilder,
                                  @Value("${SERPAPI_API_KEY:}") String apiKey) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    @Override
    public SearchProvider provider() {
        return SearchProvider.SERPAPI;
    }

    @Override
    public List<CollectedArticle> search(SearchQuery query) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("SERPAPI_API_KEY가 없어 SerpAPI 검색을 건너뛴다. queryText={}", query.queryText());
            return List.of();
        }

        try {
            NewsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(SEARCH_PATH)
                            .queryParam("engine", GOOGLE_NEWS_ENGINE)
                            .queryParam("q", query.queryText())
                            .queryParam("hl", query.languageOr(DEFAULT_LANGUAGE))
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(NewsResponse.class);

            return toArticles(response, query);
        } catch (RestClientException e) {
            return emptyOnFailure(query, e);
        }
    }

    /**
     * SerpAPI는 결과 수를 요청 파라미터로 받지 않고 한 페이지를 통째로 돌려준다. batchSize는 받은 뒤에 자른다.
     */
    private List<CollectedArticle> toArticles(NewsResponse response, SearchQuery query) {
        if (response == null || response.newsResults() == null) {
            return List.of();
        }

        return response.newsResults().stream()
                .filter(result -> StringUtils.hasText(result.link()))
                .limit(query.batchSize())
                .map(result -> toArticle(result, query))
                .toList();
    }

    private CollectedArticle toArticle(NewsResponse.Result result, SearchQuery query) {
        return new CollectedArticle(
                HtmlTextSanitizer.sanitize(result.title()),
                result.link(),
                HtmlTextSanitizer.sanitize(result.snippet()),
                CollectedArticleConverter.toPublishedAt(result.date()),
                sourceName(result),
                query.languageOr(DEFAULT_LANGUAGE)
        );
    }

    private String sourceName(NewsResponse.Result result) {
        if (result.source() != null && StringUtils.hasText(result.source().name())) {
            return result.source().name();
        }

        return CollectedArticleConverter.toSourceName(result.link());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NewsResponse(@JsonProperty("news_results") List<Result> newsResults) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String title, String link, String snippet, String date, Source source) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            record Source(String name) {
            }
        }
    }
}
