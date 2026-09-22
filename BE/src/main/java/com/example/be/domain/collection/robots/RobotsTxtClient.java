package com.example.be.domain.collection.robots;

import com.example.be.global.config.PublicDestinationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * robots.txt는 원래 몇 KB짜리 텍스트다. 상한이 없으면 상대가 거대한 응답을 주는 것만으로
     * 수집 프로세스의 메모리를 밀어낼 수 있다. 구글도 500KiB까지만 읽는다.
     */
    private static final int MAX_BODY_BYTES = 512 * 1024;

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
        } catch (URISyntaxException | IllegalArgumentException e) {
            return RobotsLookup.unknown(null, "INVALID_URL");
        }

        try {
            return restClient.get()
                    .uri(URI.create(robotsUrl))
                    .header("User-Agent", userAgent)
                    // retrieve() buffers both success and error bodies before a size check can run.
                    .exchange((request, response) -> read(robotsUrl, response));
        } catch (RestClientException e) {
            log.warn("robots.txt를 확인하지 못했다. reason=CONNECT_TIMEOUT");
            return RobotsLookup.unknown(robotsUrl, "CONNECT_TIMEOUT");
        }
    }

    private RobotsLookup read(String robotsUrl, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == HttpStatus.NOT_FOUND.value()) {
            return RobotsLookup.fetched(robotsUrl, RobotsRules.permitAll());
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // Neither redirects nor error pages are robots rules. Never read their bodies.
            return RobotsLookup.unknown(robotsUrl, "HTTP_" + status);
        }
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            return RobotsLookup.unknown(robotsUrl, "TOO_LARGE");
        }
        // Bound decoded bytes as well: chunked and compressed responses may have no useful length.
        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return RobotsLookup.unknown(robotsUrl, "TOO_LARGE");
        }
        return RobotsLookup.fetched(robotsUrl, RobotsRules.parse(new String(body, StandardCharsets.UTF_8), userAgent));
    }

    private String robotsUrlOf(String sourceUrl) throws URISyntaxException {
        URI uri = new URI(sourceUrl);
        PublicDestinationPolicy.validate(uri);

        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), ROBOTS_PATH, null, null).toString();
    }
}
