package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.connector.SearchConnector;
import com.example.be.domain.collection.connector.SearchConnectorRegistry;
import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.feed.FeedClient;
import com.example.be.domain.collection.feed.FeedFetch;
import com.example.be.domain.collection.feed.FeedRequest;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.robots.RobotsPolicyService;
import com.example.be.domain.sources.entity.SearchProvider;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 조합(주제 × 소스) 하나를 수집한다.
 *
 * <p><b>이 클래스에는 트랜잭션이 없다.</b> robots.txt 조회, 피드·검색 호출, 크롤 간격 대기, 백오프 대기가
 * 전부 여기서 일어난다. 크롤 간격 상한만 30초이고 재시도까지 붙는데, 이걸 트랜잭션 안에서 하면
 * 외부 I/O 내내 DB 커넥션을 붙잡는다. 저장은 {@link CollectionResultWriter}의 짧은 트랜잭션이 맡는다.
 *
 * <p>조합 하나가 실패해도 예외를 밖으로 던지지 않는다 — 실행 상태는 그 결과들을 합쳐서 정해진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionExecutor {

    private final FeedClient feedClient;
    private final RobotsPolicyService robotsPolicyService;
    private final SearchConnectorRegistry searchConnectorRegistry;

    public CollectionBatch collect(Long itemId, Topic topic, Source source, boolean forceRefresh) {
        try {
            return CollectionBatch.success(itemId, topic, source,
                    collectOutcome(topic, source, forceRefresh));
        } catch (RuntimeException e) {
            log.warn("조합 수집에 실패했다. topicId={} sourceId={} error={}",
                    topic.getId(), source.getId(), e.getMessage(), e);
            return CollectionBatch.failure(itemId, topic, source, messageOf(e));
        }
    }

    /**
     * Agent가 승인받은 추가 수집을 실행한다. SEARCH는 제안된 질의를 쓰고 FEED는 조건부 GET을
     * 건너뛰어 현재 피드를 다시 읽는다. 저장은 호출자가 별도 짧은 트랜잭션으로 수행한다.
     */
    public CollectionOutcome collectInvestigation(String queryText,
                                                  int batchSize,
                                                  Source source) {
        if (source.isSearchKind()) {
            SearchProvider provider = SearchProvider.fromKey(source.getUrlTemplate());
            if (provider == null) {
                return CollectionOutcome.of(
                        FetchResult.unreadable("어댑터가 없는 SEARCH 소스다: " + source.getUrlTemplate()),
                        RobotsDecision.skipped(source));
            }
            FetchResult result = searchConnectorRegistry.find(provider)
                    .map(connector -> connector.search(
                            new SearchQuery(queryText, batchSize, source.getLanguage())))
                    .orElseGet(() -> FetchResult.unreadable("등록된 커넥터가 없다: " + provider));
            return CollectionOutcome.of(result, RobotsDecision.skipped(source));
        }

        RobotsDecision robots = robotsPolicyService.evaluate(source);
        if (!robots.allowed()) {
            return CollectionOutcome.blockedByRobots(robots);
        }
        FeedFetch fetch = feedClient.fetch(FeedRequest.of(source, robots.crawlDelay(), true));
        return new CollectionOutcome(fetch.result(), robots, fetch.notModified(),
                fetch.etag(), fetch.lastModified(), fetch.result().success());
    }

    /**
     * 외부 호출과 대기만 한다. DB는 건드리지 않는다.
     */
    private CollectionOutcome collectOutcome(Topic topic, Source source, boolean forceRefresh) {
        if (source.isSearchKind()) {
            return CollectionOutcome.of(searchOf(topic, source), RobotsDecision.skipped(source));
        }

        RobotsDecision robots = robotsPolicyService.evaluate(source);
        if (!robots.allowed()) {
            return CollectionOutcome.blockedByRobots(robots);
        }

        FeedFetch fetch = feedClient.fetch(FeedRequest.of(source, robots.crawlDelay(), forceRefresh));
        return new CollectionOutcome(fetch.result(), robots, fetch.notModified(),
                fetch.etag(), fetch.lastModified(), fetch.result().success());
    }

    private FetchResult searchOf(Topic topic, Source source) {
        SearchProvider provider = SearchProvider.fromKey(source.getUrlTemplate());
        if (provider == null) {
            // provider 키가 아닌 SEARCH 소스는 #31에서 등록 단계에서 막았다. 여기 오는 건 그 전에 저장된
            // 행뿐이라 새로 생기지는 않는다. 조용히 비우지 않고 사유를 남긴다.
            log.warn("provider 키가 아닌 SEARCH 소스는 수집하지 않는다. 등록 당시의 잔여 데이터다. sourceId={} urlTemplate={}",
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

    private FetchResult search(SearchConnector connector, Topic topic, Source source) {
        if (!StringUtils.hasText(topic.getQueryText())) {
            log.warn("검색어가 없는 주제는 SEARCH 소스를 돌릴 수 없다. topicId={}", topic.getId());
            return FetchResult.unreadable("주제에 검색어가 없어 SEARCH 소스를 돌릴 수 없다.");
        }

        return connector.search(new SearchQuery(topic.getQueryText(), topic.getBatchSize(), source.getLanguage()));
    }

    private String messageOf(RuntimeException exception) {
        return StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }
}
