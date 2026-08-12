package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 세 커넥터가 똑같이 필요로 하는 처리. provider마다 다른 건 응답 모양뿐이고 실패 처리·발행일 파싱·매체명은 같다.
 */
final class ConnectorSupport {

    private static final DateTimeFormatter[] PUBLISHED_AT_FORMATS = {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
    };

    private ConnectorSupport() {
    }

    /**
     * 실패 사유를 남기고 빈 목록을 돌려준다.
     *
     * <p>4xx와 429·5xx를 갈라서 찍는 이유는, 키가 틀린 것(401)과 쿼터를 넘긴 것(429)을 같은 로그로 뭉뚱그리면
     * 원인 파악이 불가능해지기 때문이다. 4xx는 몇 번을 다시 불러도 같은 답이 온다.
     * 재시도와 지수 백오프는 F6(M3)에서 붙인다.
     */
    static List<CollectedArticle> emptyOnFailure(Logger log,
                                                 SearchProvider provider,
                                                 SearchQuery query,
                                                 RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            if (isRetryable(response)) {
                log.warn("{} 검색이 일시적으로 실패했다. M3에서 재시도 대상이다. status={} queryText={}",
                        provider, response.getStatusCode(), query.queryText());
            } else {
                log.warn("{} 검색 요청이 거부됐다. 재시도해도 같은 응답이라 여기서 멈춘다. status={} queryText={}",
                        provider, response.getStatusCode(), query.queryText());
            }
        } else {
            log.warn("{} 검색 호출에 실패했다. queryText={} error={}",
                    provider, query.queryText(), exception.getMessage());
        }

        return List.of();
    }

    /**
     * 파싱에 실패하면 null이다. 현재 시각으로 채우면 발행일이 틀렸다는 사실이 사라지고 "최근 기사" 필터가 조용히 망가진다.
     */
    static OffsetDateTime parsePublishedAt(Logger log, String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        for (DateTimeFormatter format : PUBLISHED_AT_FORMATS) {
            try {
                return OffsetDateTime.parse(text.trim(), format);
            } catch (DateTimeParseException ignored) {
                // 다음 형식으로 넘어간다.
            }
        }

        log.debug("발행일을 읽지 못해 비워 둔다. pubDate={}", text);
        return null;
    }

    /**
     * 매체명을 따로 주지 않는 provider가 있어 원문 URL의 호스트로 대신한다.
     */
    static String hostOf(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }

        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean isRetryable(RestClientResponseException response) {
        return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
    }
}
