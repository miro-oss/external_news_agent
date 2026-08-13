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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조합(주제 × 소스) 하나를 실제로 수집한다.
 *
 * <p>실행 전체를 여는·닫는 일은 호출부(runs API)가 하고, 여기서는 조합 하나만 책임진다.
 * 조합 하나가 실패해도 예외를 밖으로 던지지 않는다 — 실행 상태는 그 결과들을 합쳐서 정해진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionExecutor {

    /** 소스에 정책이 없을 때 한 번에 가져올 최대 건수. */
    private static final int DEFAULT_MAX_ARTICLES_PER_RUN = 30;

    private final FeedClient feedClient;
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
    public void execute(CollectionRun run, CollectionRunItem item, Topic topic, Source source) {
        try {
            List<CollectedArticle> collected = collect(topic, source);
            List<CollectedArticle> matched = TopicKeywordFilter.filter(topic, collected)
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
            run.addWarning(CollectionRunWarning.builder()
                    .source(source)
                    .code(CollectionRunWarning.CODE_FEED_UNREADABLE)
                    .message(messageOf(e))
                    .articleCount(0)
                    .occurredAt(LocalDateTime.now())
                    .build());
        }
    }

    private List<CollectedArticle> collect(Topic topic, Source source) {
        if (!source.isSearchKind()) {
            return feedClient.fetch(source.getUrlTemplate(), source.getLanguage());
        }

        SearchProvider provider = SearchProvider.fromKey(source.getUrlTemplate());
        if (provider == null) {
            // {query} 자리표시자를 쓰는 SEARCH 소스는 아직 어댑터가 없다. 조용히 비우지 않고 남긴다.
            log.warn("provider 키가 아닌 SEARCH 소스는 아직 수집하지 않는다. sourceId={} urlTemplate={}",
                    source.getId(), source.getUrlTemplate());
            return List.of();
        }

        return searchConnectorRegistry.find(provider)
                .map(connector -> search(connector, topic, source))
                .orElseGet(() -> {
                    log.warn("등록된 커넥터가 없다. provider={}", provider);
                    return List.of();
                });
    }

    private List<CollectedArticle> search(SearchConnector connector, Topic topic, Source source) {
        if (!StringUtils.hasText(topic.getQueryText())) {
            log.warn("검색어가 없는 주제는 SEARCH 소스를 돌릴 수 없다. topicId={}", topic.getId());
            return List.of();
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
