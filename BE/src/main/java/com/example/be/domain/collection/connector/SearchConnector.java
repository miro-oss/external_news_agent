package com.example.be.domain.collection.connector;

import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.sources.entity.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * SEARCH 소스의 url_template에 적힌 provider 키 하나를 실제 HTTP 호출로 옮기는 어댑터.
 *
 * <p>구현체는 어떤 이유로든 <b>예외를 던지지 않는다.</b> 키가 없는 개발 환경에서도 앱이 뜨고 화면이 보여야 하고,
 * 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
 *
 * <p>대신 성공과 실패를 {@link FetchResult}로 구분해서 돌려준다. 빈 목록만 주면 호출부가
 * "결과가 0건인 검색"과 "호출 실패"를 구분할 수 없어, 키가 틀린 소스도 SUCCESS 0건으로 기록된다.
 */
public interface SearchConnector {

    int TOO_MANY_REQUESTS = 429;

    SearchProvider provider();

    FetchResult search(SearchQuery query);

    /**
     * 실패 사유를 남기고 실패 결과를 만든다.
     *
     * <p>4xx와 429·5xx를 갈라서 찍는 이유는, 키가 틀린 것(401)과 쿼터를 넘긴 것(429)을 같은 로그로 뭉뚱그리면
     * 원인 파악이 불가능해지기 때문이다. 4xx는 몇 번을 다시 불러도 같은 답이 온다.
     * 재시도와 지수 백오프는 F6(M3)에서 붙인다.
     */
    default FetchResult failureOf(SearchQuery query, RestClientException exception) {
        Logger log = LoggerFactory.getLogger(getClass());

        if (exception instanceof RestClientResponseException response) {
            if (isRetryable(response)) {
                log.warn("{} 검색이 일시적으로 실패했다. F6에서 재시도 대상이다. status={} queryText={}",
                        provider(), response.getStatusCode(), query.queryText());
                return FetchResult.rateLimited(provider() + " 응답 " + response.getStatusCode());
            }

            log.warn("{} 검색 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} queryText={}",
                    provider(), response.getStatusCode(), query.queryText());
            return FetchResult.searchFailed(provider() + " 응답 " + response.getStatusCode());
        }

        log.warn("{} 검색 호출에 실패했다. queryText={} error={}",
                provider(), query.queryText(), exception.getMessage());
        return FetchResult.searchFailed(provider() + " 호출 실패: " + exception.getMessage());
    }

    /**
     * 키가 없으면 호출조차 하지 않는다. 예외를 던지지 않으므로 키 없이도 앱은 뜨고, 실제로 그 소스를 쓰는
     * 수집 실행에서만 경고로 드러난다.
     */
    default FetchResult missingKey(SearchQuery query, String keyNames) {
        LoggerFactory.getLogger(getClass())
                .warn("{}가 없어 {} 검색을 건너뛴다. queryText={}", keyNames, provider(), query.queryText());
        return FetchResult.providerKeyMissing(keyNames + "가 설정되지 않았다.");
    }

    private static boolean isRetryable(RestClientResponseException response) {
        return response.getStatusCode().value() == TOO_MANY_REQUESTS
                || response.getStatusCode().is5xxServerError();
    }
}
