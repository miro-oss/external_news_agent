package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.connector.SearchConnector;
import com.example.be.domain.collection.connector.SearchConnectorRegistry;
import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.converter.TopicKeywordFilter;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleVersion;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.feed.FeedClient;
import com.example.be.domain.collection.feed.FeedFetch;
import com.example.be.domain.collection.feed.FeedRequest;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.robots.RobotsPolicyService;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.SearchProvider;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 조합(주제 × 소스) 하나를 실제로 수집한다.
 *
 * <p>실행 전체를 여는·닫는 일은 호출부(runs API)가 하고, 여기서는 조합 하나만 책임진다.
 * 조합 하나가 실패해도 예외를 밖으로 던지지 않는다 — 실행 상태는 그 결과들을 합쳐서 정해진다.
 *
 * <p>{@code @Transactional}을 붙여 둔 이유는 여기서 엔티티를 더티 체킹으로 고치기 때문이다.
 * 경계 없이 호출되면 변경이 반영되지 않는다. <b>조합별 격리(REQUIRES_NEW)는 오케스트레이터가 생기는
 * 후속 이슈에서 정한다</b> — 실행 행이 먼저 커밋돼 있어야 하고, 그 시점을 정하는 건 runs API다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionExecutor {

    /** 소스에 정책이 없을 때 한 번에 가져올 최대 건수. */
    private static final int DEFAULT_MAX_ARTICLES_PER_RUN = 30;

    private final FeedClient feedClient;
    private final RobotsPolicyService robotsPolicyService;
    private final SearchConnectorRegistry searchConnectorRegistry;
    private final ArticleRepository articleRepository;
    private final ArticleVersionRepository articleVersionRepository;
    private final CollectionRunArticleRepository runArticleRepository;

    /**
     * 이 조합을 수집하고 결과를 {@code item}에 기록한다.
     *
     * <p>{@code scannedCount}는 <b>필터 전에 받은 건수</b>다. 키워드로 걸러진 기사도 "훑기는 했다".
     * 그래서 {@code skipped = scanned - new - updated}에는 중복과 필터 탈락이 함께 들어간다.
     */
    @Transactional
    public void execute(CollectionRun run, CollectionRunItem item, Topic topic, Source source) {
        try {
            FetchResult fetched = collect(run, item, topic, source);
            if (item.getStatus() == RunItemStatus.SKIPPED) {
                return;
            }

            if (!fetched.success()) {
                item.markFailed();
                run.addWarning(warning(source, fetched.failureCode(), fetched.failureMessage()));
                return;
            }

            List<CollectedArticle> collected = fetched.articles();
            List<CollectedArticle> matched = dedupeByUrl(TopicKeywordFilter.filter(topic, collected))
                    .stream()
                    .limit(maxArticlesPerRun(source))
                    .toList();

            int newCount = 0;
            int updatedCount = 0;

            for (CollectedArticle article : matched) {
                ChangeType changeType = save(run, topic, source, article);
                if (changeType == ChangeType.NEW) {
                    newCount++;
                } else if (changeType == ChangeType.UPDATED) {
                    updatedCount++;
                }
            }

            item.recordResult(RunItemStatus.SUCCESS, collected.size(), newCount, updatedCount);
        } catch (RuntimeException e) {
            // 조합 하나의 실패가 실행 전체를 끌어내리지 않는다. 사유는 경고로 남아 화면에서 보인다.
            log.warn("조합 수집에 실패했다. topicId={} sourceId={} error={}",
                    topic.getId(), source.getId(), e.getMessage(), e);
            item.markFailed();
            run.addWarning(warning(source, CollectionRunWarning.CODE_FEED_UNREADABLE, messageOf(e)));
        }
    }

    private CollectionRunWarning warning(Source source, String code, String message) {
        return CollectionRunWarning.builder()
                .source(source)
                .code(code)
                .message(message)
                .articleCount(0)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /**
     * 같은 실행·같은 조합에서 같은 URL이 두 번 오면 uq_run_article을 위반해 조합 전체가 실패한다.
     * 실제로 한 기사를 여러 섹션에 중복 노출하는 피드가 있다. 저장 전에 접는다.
     */
    private List<CollectedArticle> dedupeByUrl(List<CollectedArticle> articles) {
        Map<String, CollectedArticle> byUrlHash = new LinkedHashMap<>();
        articles.forEach(article -> byUrlHash.putIfAbsent(ArticleHasher.urlHash(article.canonicalUrl()), article));
        return List.copyOf(byUrlHash.values());
    }

    private FetchResult collect(CollectionRun run, CollectionRunItem item, Topic topic, Source source) {
        if (!source.isSearchKind()) {
            return collectFeed(run, item, source);
        }

        SearchProvider provider = SearchProvider.fromKey(source.getUrlTemplate());
        if (provider == null) {
            // {query} 자리표시자를 쓰는 SEARCH 소스는 아직 어댑터가 없다. 조용히 비우지 않고 남긴다.
            log.warn("provider 키가 아닌 SEARCH 소스는 아직 수집하지 않는다. sourceId={} urlTemplate={}",
                    source.getId(), source.getUrlTemplate());
            return FetchResult.unreadable("어댑터가 없는 SEARCH 소스다: " + source.getUrlTemplate());
        }

        return searchConnectorRegistry.find(provider)
                .map(connector -> search(connector, topic, source))
                .orElseGet(() -> {
                    log.warn("등록된 커넥터가 없다. provider={}", provider);
                    return FetchResult.unreadable("등록된 커넥터가 없다: " + provider);
                });
    }

    /**
     * robots를 먼저 보고, 막혔으면 요청하지 않는다. 조건부 GET으로 304가 오면 파싱도 하지 않는다.
     * 둘 다 실패가 아니라 SKIPPED다 — 정책대로 동작한 것을 FAILED로 적으면 실행이 매번 PARTIAL이 된다.
     */
    private FetchResult collectFeed(CollectionRun run, CollectionRunItem item, Source source) {
        RobotsDecision robots = robotsPolicyService.check(source);
        if (!robots.allowed()) {
            item.markSkipped();
            run.addWarning(warning(source, CollectionRunWarning.CODE_ROBOTS_DISALLOWED,
                    "robots.txt가 수집을 막는다: " + robots.robotsTxtUrl()));
            return FetchResult.ok(List.of());
        }

        FeedFetch fetch = feedClient.fetch(FeedRequest.of(source, robots.crawlDelay(), run.isForceRefresh()));
        source.applyFetchState(fetch.etag(), fetch.lastModified(), LocalDateTime.now());

        if (fetch.notModified()) {
            item.markSkipped();
            return FetchResult.ok(List.of());
        }

        return fetch.result();
    }

    private FetchResult search(SearchConnector connector, Topic topic, Source source) {
        if (!StringUtils.hasText(topic.getQueryText())) {
            log.warn("검색어가 없는 주제는 SEARCH 소스를 돌릴 수 없다. topicId={}", topic.getId());
            return FetchResult.unreadable("주제에 검색어가 없어 SEARCH 소스를 돌릴 수 없다.");
        }

        return connector.search(new SearchQuery(topic.getQueryText(), topic.getBatchSize(), source.getLanguage()));
    }

    /**
     * url_hash로 이미 있는 기사인지 보고 NEW / UPDATED / UNCHANGED를 정한다. 판정과 무관하게
     * 관측은 매번 남긴다 — 그래야 "이 실행에서 본 기사"를 나중에 복원할 수 있다.
     */
    private ChangeType save(CollectionRun run, Topic topic, Source source, CollectedArticle collected) {
        String urlHash = ArticleHasher.urlHash(collected.canonicalUrl());
        String contentHash = ArticleHasher.contentHash(collected.title(), collected.summary(), null);
        LocalDateTime now = LocalDateTime.now();

        Article article = articleRepository.findByUrlHash(urlHash).orElse(null);
        ChangeType changeType;

        if (article == null) {
            article = articleRepository.save(newArticle(run, topic, source, collected, urlHash, contentHash, now));
            changeType = ChangeType.NEW;
        } else if (article.hasSameContent(contentHash)) {
            article.markSeen(run);
            changeType = ChangeType.UNCHANGED;
        } else {
            articleVersionRepository.save(
                    ArticleVersion.snapshotOf(article, run, nextVersionNo(article.getId()), now));
            article.applyUpdate(
                    collected.title(),
                    collected.summary(),
                    article.getBody(),
                    contentHash,
                    article.getFetchStatus(),
                    run,
                    now);
            changeType = ChangeType.UPDATED;
        }

        runArticleRepository.save(
                CollectionRunArticle.observe(run, article, topic, source, changeType, now));
        return changeType;
    }

    private Article newArticle(CollectionRun run,
                               Topic topic,
                               Source source,
                               CollectedArticle collected,
                               String urlHash,
                               String contentHash,
                               LocalDateTime now) {
        return Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(urlHash)
                .canonicalUrl(collected.canonicalUrl())
                .title(collected.title())
                .summary(collected.summary())
                .contentHash(contentHash)
                .language(collected.language())
                .sourceName(collected.sourceName())
                .publishedAt(collected.publishedAt())
                // 전문은 F6이 따로 받는다. RSS의 description은 잘린 요약이다.
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(now)
                .build();
    }

    private int nextVersionNo(Long articleId) {
        return articleVersionRepository.findFirstByArticleIdOrderByVersionNoDesc(articleId)
                .map(version -> version.getVersionNo() + 1)
                .orElse(ArticleVersion.FIRST_VERSION_NO);
    }

    private int maxArticlesPerRun(Source source) {
        CrawlPolicy policy = source.getCrawlPolicy();
        if (policy == null || policy.maxArticlesPerRun() == null || policy.maxArticlesPerRun() <= 0) {
            return DEFAULT_MAX_ARTICLES_PER_RUN;
        }

        return policy.maxArticlesPerRun();
    }

    private String messageOf(RuntimeException exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return exception.getClass().getSimpleName();
        }

        return message.length() > CollectionRunWarning.MAX_MESSAGE_LENGTH
                ? message.substring(0, CollectionRunWarning.MAX_MESSAGE_LENGTH)
                : message;
    }
}
