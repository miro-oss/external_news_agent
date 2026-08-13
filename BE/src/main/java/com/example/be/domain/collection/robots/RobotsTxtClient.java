package com.example.be.domain.collection.robots;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 소스 URL의 호스트에서 robots.txt를 받아 해석한다.
 *
 * <p>robots.txt가 <b>없는 것</b>과 <b>못 받은 것</b>을 구분한다. 404는 "제한이 없다"는 뜻이라 허용이고,
 * 타임아웃이나 5xx는 판단할 근거가 없어 {@code unknown}이다. 명세도 조회 실패를 disallowed가 아니라
 * unknown으로 저장하라고 적고 있다.
 */
@Slf4j
@Component
public class RobotsTxtClient {

    private static final String ROBOTS_PATH = "/robots.txt";

    private final RestClient restClient;
    private final String userAgent;

    public RobotsTxtClient(RestClient.Builder restClientBuilder,
                           @Value("${news.collection.user-agent:external-news-agent}") String userAgent) {
        this.restClient = restClientBuilder.build();
        this.userAgent = userAgent;
    }

    public String userAgent() {
        return userAgent;
    }

    public RobotsLookup lookup(String sourceUrl) {
        String robotsUrl;
        try {
            robotsUrl = robotsUrlOf(sourceUrl);
        } catch (URISyntaxException e) {
            return RobotsLookup.unknown(null, "INVALID_URL");
        }

        try {
            String body = restClient.get()
                    .uri(robotsUrl)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(String.class);

            return RobotsLookup.fetched(robotsUrl, RobotsRules.parse(body, userAgent));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // robots.txt가 없는 사이트는 흔하다. 없으면 제한이 없다는 뜻이다.
                log.debug("robots.txt가 없다. 제한 없음으로 본다. url={} status={}", robotsUrl, e.getStatusCode());
                return RobotsLookup.fetched(robotsUrl, RobotsRules.permitAll());
            }

            log.warn("robots.txt를 확인하지 못했다. url={} status={}", robotsUrl, e.getStatusCode());
            return RobotsLookup.unknown(robotsUrl, "HTTP_" + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("robots.txt를 확인하지 못했다. url={} error={}", robotsUrl, e.getMessage());
            return RobotsLookup.unknown(robotsUrl, "CONNECT_TIMEOUT");
        }
    }

    private String robotsUrlOf(String sourceUrl) throws URISyntaxException {
        URI uri = new URI(sourceUrl);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new URISyntaxException(sourceUrl, "스킴이나 호스트가 없다.");
        }

        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), ROBOTS_PATH, null, null).toString();
    }
}
