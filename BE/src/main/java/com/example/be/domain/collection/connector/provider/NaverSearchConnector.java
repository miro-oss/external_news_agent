package com.example.be.domain.collection.connector.provider;

import com.example.be.domain.collection.connector.SearchConnector;
import com.example.be.domain.collection.connector.converter.CollectedArticleConverter;
import com.example.be.domain.collection.connector.converter.HtmlTextSanitizer;
import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.sources.entity.SearchProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * NAVER API HUB 뉴스 검색. 한국어 뉴스의 1차 수집 provider다 (plan-final §2-6).
 */
@Slf4j
@Component
public class NaverSearchConnector implements SearchConnector {

    private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";
    private static final String NEWS_PATH = "/search/v1/news";

    private static final String CLIENT_ID_HEADER = "X-NCP-APIGW-API-KEY-ID";
    private static final String CLIENT_SECRET_HEADER = "X-NCP-APIGW-API-KEY";

    /** 최신 기사를 먼저 받는다. 수집 주기가 짧아 정확도순으로 받으면 새 기사를 놓친다. */
    private static final String SORT_BY_DATE = "date";

    /** 네이버 뉴스 검색은 한국어 매체만 돌려준다. 주제에 어떤 언어가 적혀 있든 결과는 한국어다. */
    private static final String LANGUAGE = "ko";

    /** NAVER 뉴스 검색 API의 display 요청 상한이다. */
    private static final int MAX_PAGE_SIZE = 100;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    public NaverSearchConnector(RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper,
                                @Value("${NAVER_CLIENT_ID:}") String clientId,
                                @Value("${NAVER_CLIENT_SECRET:}") String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public SearchProvider provider() {
        return SearchProvider.NAVER;
    }

    @Override
    public FetchResult search(SearchQuery query) {
        if (!hasCredentials()) {
            return missingKey(query, "NAVER_CLIENT_ID/NAVER_CLIENT_SECRET");
        }

        try {
            return FetchResult.ok(fetchArticles(query));
        } catch (RestClientException e) {
            return failureOf(query, e);
        } catch (JacksonException e) {
            return failureOf(query, new RestClientException("NAVER 응답 JSON 파싱 실패", e));
        }
    }

    private List<CollectedArticle> fetchArticles(SearchQuery query) {
        List<CollectedArticle> articles = new ArrayList<>();
        int remaining = query.batchSize();
        int start = 1;

        while (remaining > 0) {
            int display = Math.min(remaining, MAX_PAGE_SIZE);
            NewsResponse response = requestPage(query, display, start);
            List<NewsResponse.Item> items = itemsOf(response);

            articles.addAll(toArticles(items));
            if (items.size() < display) {
                break;
            }

            start += display;
            remaining -= display;
        }

        return articles;
    }

    private NewsResponse requestPage(SearchQuery query, int display, int start) {
        String responseBody = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(NEWS_PATH)
                        .queryParam("query", query.queryText())
                        .queryParam("display", display)
                        .queryParam("start", start)
                        .queryParam("sort", SORT_BY_DATE)
                        .build())
                .header(CLIENT_ID_HEADER, clientId)
                .header(CLIENT_SECRET_HEADER, clientSecret)
                .retrieve()
                .body(String.class);

        return parseResponse(responseBody);
    }

    private boolean hasCredentials() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    /** API HUB는 JSON 본문을 {@code text/plain;charset=UTF-8}로 응답할 수 있어 문자열로 받은 뒤 파싱한다. */
    private NewsResponse parseResponse(String responseBody) {
        return StringUtils.hasText(responseBody)
                ? objectMapper.readValue(responseBody, NewsResponse.class)
                : null;
    }

    private List<NewsResponse.Item> itemsOf(NewsResponse response) {
        return response == null || response.items() == null ? List.of() : response.items();
    }

    private List<CollectedArticle> toArticles(List<NewsResponse.Item> items) {
        return items.stream()
                .filter(this::hasOriginalLink)
                .map(this::toArticle)
                .toList();
    }

    /**
     * {@code originallink}가 없으면 남는 URL은 네이버 미러뿐이라 기사를 버린다. 미러를 canonicalUrl로 저장하면
     * 본문 추출이 네이버 페이지 구조에 묶이고 중복 제거 키도 원문과 어긋난다 (plan-final §2-6).
     */
    private boolean hasOriginalLink(NewsResponse.Item item) {
        if (StringUtils.hasText(item.originalLink())) {
            return true;
        }

        log.debug("originallink가 없어 건너뛴다. link={}", item.link());
        return false;
    }

    private CollectedArticle toArticle(NewsResponse.Item item) {
        String canonicalUrl = item.originalLink();

        return new CollectedArticle(
                HtmlTextSanitizer.sanitize(item.title()),
                canonicalUrl,
                HtmlTextSanitizer.sanitize(item.description()),
                CollectedArticleConverter.toPublishedAt(item.pubDate()),
                CollectedArticleConverter.toSourceName(canonicalUrl),
                LANGUAGE
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NewsResponse(List<Item> items) {

        /**
         * {@code link}는 네이버 뉴스 미러이고 {@code originallink}가 언론사 원문이다. 매핑에서 쓰는 건 후자뿐이다.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(String title,
                    @JsonProperty("originallink") String originalLink,
                    String link,
                    String description,
                    String pubDate) {
        }
    }
}
