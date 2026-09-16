package com.example.be.domain.collection.content;

import com.example.be.domain.collection.ratelimit.Backoff;
import com.example.be.domain.collection.ratelimit.DomainRateLimiter;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.global.config.PublicDestinationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;

/** Fetches bounded public article bodies and reports failures without exposing request data. */
@Slf4j
@Component
public class ArticleContentClient {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final RestClient restClient;
    private final DomainRateLimiter rateLimiter;
    private final RobotsTxtClient robotsTxtClient;
    private final String userAgent;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public ArticleContentClient(RestClient.Builder restClientBuilder,
                                DomainRateLimiter rateLimiter,
                                RobotsTxtClient robotsTxtClient,
                                @Value("${news.collection.user-agent:external-news-agent}") String userAgent,
                                @Value("${news.collection.retry.max-attempts:3}") int maxAttempts,
                                @Value("${news.collection.retry.base-delay-ms:1000}") long backoffBaseMs,
                                @Value("${news.collection.retry.max-delay-ms:8000}") long backoffMaxMs) {
        this.restClient = restClientBuilder.build();
        this.rateLimiter = rateLimiter;
        this.robotsTxtClient = robotsTxtClient;
        this.userAgent = userAgent;
        this.maxAttempts = maxAttempts;
        this.backoffBase = Duration.ofMillis(backoffBaseMs);
        this.backoffMax = Duration.ofMillis(backoffMaxMs);
    }

    public ArticleContentResult fetch(String articleUrl, Duration crawlDelay) {
        return fetch(articleUrl, crawlDelay, null);
    }

    public ArticleContentResult fetch(String articleUrl, Duration crawlDelay, String articleTitle) {
        return fetch(articleUrl, crawlDelay, articleTitle, true);
    }

    /** Preserve the source's explicit ignore policy across redirects; default callers respect robots. */
    public ArticleContentResult fetch(String articleUrl, Duration crawlDelay, String articleTitle,
                                      boolean respectsRobots) {
        if (Thread.currentThread().isInterrupted()) {
            return ArticleContentResult.failed(INTERRUPTED);
        }
        URI current;
        try {
            current = httpUri(articleUrl);
        } catch (PublicDestinationPolicy.RejectedDestinationException exception) {
            return ArticleContentResult.failed(OUTBOUND_POLICY);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ArticleContentResult.failed(INVALID_URL);
        }
        try {
            return fetchValidated(current, crawlDelay, articleTitle, respectsRobots);
        } catch (RuntimeException exception) {
            return exceptionResult(exception);
        }
    }

    private ArticleContentResult fetchValidated(URI current, Duration crawlDelay, String articleTitle,
                                                boolean respectsRobots) {
        Set<URI> visited = new HashSet<>();
        visited.add(current);
        Duration currentDelay = respectsRobots ? crawlDelay : null;
        for (int redirects = 0; ; redirects++) {
            if (Thread.currentThread().isInterrupted()) {
                return ArticleContentResult.failed(INTERRUPTED);
            }
            if (redirects > 0 && respectsRobots) {
                // Reserve slots for robots.txt and the article separately.
                if (!await(current.toString(), null)) {
                    return ArticleContentResult.failed(INTERRUPTED);
                }
                RobotsLookup robots = robotsTxtClient.lookup(current.toString());
                if (!robots.allows(current.toString())) {
                    return ArticleContentResult.robotsDisallowed();
                }
                currentDelay = robots.rules().crawlDelay();
            }
            Attempt attempt = request(current.toString(), currentDelay, articleTitle);
            if (attempt.resource() != null) {
                PublicArticleResource.Resource resource = attempt.resource();
                return fetchSecondary(resource.uri(), respectsRobots, MediaType.APPLICATION_JSON,
                        (bytes, charset) -> PublicArticleResource.extract(bytes, resource));
            }
            if (attempt.alternative() != null) {
                PublicArticleAlternative.Candidate alternative = attempt.alternative();
                return fetchSecondary(alternative.uri(), respectsRobots, MediaType.TEXT_HTML,
                        (bytes, charset) -> PublicArticleAlternative.extract(bytes, charset, alternative));
            }
            if (attempt.location() == null) {
                return attempt.result();
            }
            if (redirects == MAX_REDIRECTS) {
                return ArticleContentResult.failed(REDIRECT_REJECTED);
            }
            URI next;
            try {
                next = httpUri(current.resolve(attempt.location()).toString());
            } catch (PublicDestinationPolicy.RejectedDestinationException exception) {
                return ArticleContentResult.failed(OUTBOUND_POLICY);
            } catch (IllegalArgumentException exception) {
                return ArticleContentResult.failed(REDIRECT_REJECTED);
            }
            if ((current.getScheme().equalsIgnoreCase("https") && next.getScheme().equalsIgnoreCase("http"))
                    || !visited.add(next)) {
                return ArticleContentResult.failed(REDIRECT_REJECTED);
            }
            current = next;
        }
    }

    private Attempt request(String articleUrl, Duration crawlDelay, String articleTitle) {
        for (int tries = 1; tries <= maxAttempts; tries++) {
            if (!await(articleUrl, crawlDelay)) {
                return Attempt.failed(INTERRUPTED);
            }
            Attempt attempt;
            try {
                attempt = restClient.get()
                        // A resolved URI must not be re-encoded as a URI template.
                        .uri(URI.create(articleUrl))
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        // Spring closes every response, including unread error/oversize bodies.
                        .exchange((request, response) -> read(articleUrl, articleTitle, response));
            } catch (RuntimeException exception) {
                ArticleContentResult failure = exceptionResult(exception);
                attempt = new Attempt(failure, failure.reason().retryable(), null, null, null);
            }
            if (!attempt.retryable() || tries == maxAttempts) {
                return attempt;
            }
            if (!sleep(Backoff.delayAfter(tries, backoffBase, backoffMax))) {
                return Attempt.failed(INTERRUPTED);
            }
        }
        return Attempt.failed(UNKNOWN);
    }

    private static URI httpUri(String value) {
        URI uri = URI.create(value).normalize();
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Article URL must be absolute");
        }
        PublicDestinationPolicy.validate(uri);
        // Fragments are not sent to the server and must not defeat loop detection.
        return URI.create(uri.toString().split("#", 2)[0]);
    }

    private Attempt read(String articleUrl, String articleTitle, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is3xxRedirection()) {
            int value = status.value();
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if ((value == 301 || value == 302 || value == 303 || value == 307 || value == 308)
                    && location != null && !location.isBlank()) {
                return new Attempt(null, false, location.strip(), null, null);
            }
            return Attempt.failed(REDIRECT_REJECTED);
        }
        ArticleContentFailureReason statusReason = statusReason(status);
        if (statusReason == HTTP_ACCESS_DENIED) {
            return new Attempt(ArticleContentResult.blocked(), false, null, null, null);
        }
        if (statusReason != NONE) {
            return Attempt.failed(statusReason);
        }
        MediaType type;
        try {
            type = response.getHeaders().getContentType();
            if (type != null && !isHtml(type)) {
                return Attempt.failed(UNSUPPORTED_CONTENT_TYPE);
            }
        } catch (InvalidMediaTypeException exception) {
            return Attempt.failed(UNSUPPORTED_CONTENT_TYPE);
        }
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            return Attempt.failed(BODY_TOO_LARGE);
        }
        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return Attempt.failed(BODY_TOO_LARGE);
        }
        String charset = charsetOf(type);
        String content = ArticleContentExtractor.extract(body, charset, articleUrl, articleTitle);
        if (content != null) {
            return new Attempt(ArticleContentResult.fullText(content), false, null, null, null);
        }
        PublicArticleResource.Resource resource = PublicArticleResource.find(body, charset, articleUrl);
        PublicArticleAlternative.Candidate alternative = resource == null
                ? PublicArticleAlternative.find(body, charset, articleUrl) : null;
        return new Attempt(ArticleContentResult.failed(BODY_NOT_FOUND), false, null, resource, alternative);
    }

    /** Retries only the proven URI; redirects and further fallback discovery remain disabled. */
    private ArticleContentResult fetchSecondary(URI uri, boolean respectsRobots, MediaType expectedType,
                                                BodyParser parser) {
        httpUri(uri.toString());
        String url = uri.toString();
        Duration crawlDelay = null;
        if (respectsRobots) {
            if (!await(url, null)) {
                return ArticleContentResult.failed(INTERRUPTED);
            }
            RobotsLookup robots = robotsTxtClient.lookup(url);
            if (!robots.allows(url)) {
                return ArticleContentResult.robotsDisallowed();
            }
            if ("HTTP_401".equals(robots.reason()) || "HTTP_403".equals(robots.reason())
                    || "HTTP_451".equals(robots.reason())) {
                return ArticleContentResult.failed(HTTP_ACCESS_DENIED);
            }
            crawlDelay = robots.rules().crawlDelay();
        }
        for (int tries = 1; tries <= maxAttempts; tries++) {
            if (!await(url, crawlDelay)) {
                return ArticleContentResult.failed(INTERRUPTED);
            }
            ArticleContentResult result;
            try {
                result = restClient.get().uri(uri).header(HttpHeaders.USER_AGENT, userAgent)
                        .exchange((request, response) -> readSecondary(response, expectedType, parser));
            } catch (RuntimeException exception) {
                result = exceptionResult(exception);
            }
            if (!result.reason().retryable() || tries == maxAttempts) {
                return result;
            }
            if (!sleep(Backoff.delayAfter(tries, backoffBase, backoffMax))) {
                return ArticleContentResult.failed(INTERRUPTED);
            }
        }
        return ArticleContentResult.failed(UNKNOWN);
    }

    private ArticleContentResult readSecondary(ClientHttpResponse response, MediaType expectedType, BodyParser parser)
            throws IOException {
        ArticleContentFailureReason reason = statusReason(response.getStatusCode());
        if (reason != NONE) {
            return ArticleContentResult.failed(reason);
        }
        MediaType type;
        try {
            type = response.getHeaders().getContentType();
            if (type == null || !(expectedType.equals(MediaType.TEXT_HTML)
                    ? isHtml(type) : expectedType.isCompatibleWith(type))) {
                return ArticleContentResult.failed(UNSUPPORTED_CONTENT_TYPE);
            }
        } catch (InvalidMediaTypeException exception) {
            return ArticleContentResult.failed(UNSUPPORTED_CONTENT_TYPE);
        }
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            return ArticleContentResult.failed(BODY_TOO_LARGE);
        }
        byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            return ArticleContentResult.failed(BODY_TOO_LARGE);
        }
        String body = parser.extract(bytes, charsetOf(type));
        return body == null ? ArticleContentResult.failed(RESOURCE_INVALID) : ArticleContentResult.fullText(body);
    }

    private static ArticleContentFailureReason statusReason(HttpStatusCode status) {
        int value = status.value();
        if (value == 401 || value == 403 || value == 451) {
            return HTTP_ACCESS_DENIED;
        }
        if (value == 429) {
            return HTTP_RATE_LIMITED;
        }
        if (status.is5xxServerError()) {
            return HTTP_SERVER_ERROR;
        }
        if (status.is4xxClientError()) {
            return HTTP_CLIENT_ERROR;
        }
        if (status.is3xxRedirection()) {
            return REDIRECT_REJECTED;
        }
        return status.is2xxSuccessful() ? NONE : UNEXPECTED_ERROR;
    }

    private static boolean isHtml(MediaType type) {
        return MediaType.TEXT_HTML.isCompatibleWith(type) || MediaType.APPLICATION_XHTML_XML.isCompatibleWith(type);
    }

    private static String charsetOf(MediaType type) {
        return type == null || type.getCharset() == null ? null : type.getCharset().name();
    }

    private static ArticleContentResult exceptionResult(RuntimeException exception) {
        ArticleContentFailureReason reason = ArticleContentFailureClassifier.classify(exception);
        if (reason == INTERRUPTED) {
            Thread.currentThread().interrupt();
        }
        log.debug("본문 호출에 실패했다. reason={}", reason);
        return ArticleContentResult.failed(reason);
    }

    private boolean await(String url, Duration crawlDelay) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        rateLimiter.await(url, crawlDelay);
        return !Thread.currentThread().isInterrupted();
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return !Thread.currentThread().isInterrupted();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    private interface BodyParser {
        String extract(byte[] bytes, String charset);
    }

    private record Attempt(ArticleContentResult result, boolean retryable, String location,
                           PublicArticleResource.Resource resource, PublicArticleAlternative.Candidate alternative) {
        static Attempt failed(ArticleContentFailureReason reason) {
            return new Attempt(ArticleContentResult.failed(reason), reason.retryable(), null, null, null);
        }
    }
}
