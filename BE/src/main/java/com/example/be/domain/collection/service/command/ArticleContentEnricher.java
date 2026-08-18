package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수집이 끝난 뒤 이번 실행에서 본 기사의 본문을 채운다.
 *
 * <p>RSS의 {@code description}은 잘린 요약이라 전문은 기사 URL을 다시 방문해야 얻는다.
 * 피드 하나당 한 번이던 요청이 <b>기사 수만큼</b> 늘어나는 자리라 robots·간격·백오프를 그대로 지킨다.
 *
 * <p>{@link CollectionExecutor}와 같은 규칙이다 — <b>이 클래스에는 트랜잭션이 없다.</b>
 * HTTP와 대기는 여기서, 저장은 {@link CollectionResultWriter}의 짧은 트랜잭션이 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleContentEnricher {

    private final ArticleRepository articleRepository;
    private final ArticleContentClient contentClient;
    private final RobotsTxtClient robotsTxtClient;
    private final CollectionResultWriter resultWriter;

    public Set<Long> enrich(Long runId) {
        List<Article> targets = articleRepository.findFullTextTargetsByRunId(runId);
        if (targets.isEmpty()) {
            return Set.of();
        }

        // 기사마다 robots.txt를 받으면 요청이 두 배가 된다. 실행 안에서 호스트별로 한 번만 본다.
        Map<String, RobotsLookup> robotsByHost = new HashMap<>();
        Map<Long, Integer> blockedCountBySource = new LinkedHashMap<>();
        Set<Long> refreshedArticleIds = new LinkedHashSet<>();

        for (Article article : targets) {
            ArticleContentResult result = fetch(article, robotsByHost);
            if (result == null) {
                continue;
            }

            resultWriter.applyFullText(article.getId(), result.status(), result.body());
            if (result.status() == FetchStatus.FULLTEXT) {
                refreshedArticleIds.add(article.getId());
            }
            if (result.status() == FetchStatus.FULLTEXT_BLOCKED) {
                blockedCountBySource.merge(article.getSource().getId(), 1, Integer::sum);
            }
        }

        blockedCountBySource.forEach((sourceId, count) ->
                resultWriter.addFullTextBlockedWarning(runId, sourceId, count));
        return Set.copyOf(refreshedArticleIds);
    }

    /**
     * @return 반영할 결과. 정책상 아예 시도하지 않는 경우에는 null이라 기사는 METADATA_ONLY로 남는다.
     */
    private ArticleContentResult fetch(Article article, Map<String, RobotsLookup> robotsByHost) {
        Source source = article.getSource();
        if (!allowsFullText(source)) {
            // plan-final §4-3. 페이월 매체는 정책으로 꺼 두므로 요청 자체를 하지 않는다.
            log.debug("fullTextAllowed=false라 본문을 받지 않는다. sourceId={}", source.getId());
            return null;
        }

        String url = article.getCanonicalUrl();
        String host = hostOf(url);
        if (host == null) {
            return ArticleContentResult.failed();
        }

        // robotsMode=ignore면 조회조차 하지 않는다. RobotsPolicyService와 해석을 맞춘다 —
        // 여기서만 robots를 받아 crawl-delay까지 적용하면 같은 정책이 경로마다 다르게 동작한다.
        if (!source.respectsRobots()) {
            return contentClient.fetch(url, null);
        }

        // robots는 기사 URL의 호스트 기준이다. 구글 뉴스 RSS처럼 소스와 기사 호스트가 다른 경우가 있다.
        RobotsLookup robots = robotsByHost.computeIfAbsent(host, ignored -> robotsTxtClient.lookup(url));
        if (!robots.allows(url)) {
            log.debug("robots.txt가 본문 수집을 막는다. url={}", url);
            return ArticleContentResult.robotsDisallowed();
        }

        return contentClient.fetch(url, robots.rules().crawlDelay());
    }

    private boolean allowsFullText(Source source) {
        CrawlPolicy policy = source.getCrawlPolicy();
        return policy == null || !Boolean.FALSE.equals(policy.fullTextAllowed());
    }

    private String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
