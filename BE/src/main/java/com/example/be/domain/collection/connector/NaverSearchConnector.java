package com.example.be.domain.collection.connector;

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
 * 네이버 뉴스 검색. 한국어 뉴스의 1차 수집 provider다 (plan-final §2-6).
 */
@Slf4j
@Component
public class NaverSearchConnector implements SearchConnector {

    private static final String BASE_URL = "https://openapi.naver.com";
    private static final String NEWS_PATH = "/v1/search/news.json";

    private static final String CLIENT_ID_HEADER = "X-Naver-Client-Id";
    private static final String CLIENT_SECRET_HEADER = "X-Naver-Client-Secret";

    /** 최신 기사를 먼저 받는다. 수집 주기가 짧아 정확도순으로 받으면 새 기사를 놓친다. */
    private static final String SORT_BY_DATE = "date";

    /** 네이버 뉴스 검색은 한국어 매체만 돌려준다. */
    private static final String LANGUAGE = "ko";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public NaverSearchConnector(RestClient.Builder restClientBuilder,
                                @Value("${NAVER_CLIENT_ID:}") String clientId,
                                @Value("${NAVER_CLIENT_SECRET:}") String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public SearchProvider provider() {
        return SearchProvider.NAVER;
    }

    @Override
    public List<CollectedArticle> search(SearchQuery query) {
        if (!hasCredentials()) {
            log.warn("NAVER_CLIENT_ID/NAVER_CLIENT_SECRET이 없어 네이버 검색을 건너뛴다. queryText={}", query.queryText());
            return List.of();
        }

        try {
            NewsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(NEWS_PATH)
                            .queryParam("query", query.queryText())
                            .queryParam("display", query.batchSize())
                            .queryParam("sort", SORT_BY_DATE)
                            .build())
                    .header(CLIENT_ID_HEADER, clientId)
                    .header(CLIENT_SECRET_HEADER, clientSecret)
                    .retrieve()
                    .body(NewsResponse.class);

            return toArticles(response);
        } catch (RestClientException e) {
            return ConnectorSupport.emptyOnFailure(log, provider(), query, e);
        }
    }

    private boolean hasCredentials() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    private List<CollectedArticle> toArticles(NewsResponse response) {
        if (response == null || response.items() == null) {
            return List.of();
        }

        return response.items().stream()
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
                ConnectorSupport.parsePublishedAt(log, item.pubDate()),
                ConnectorSupport.hostOf(canonicalUrl),
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
