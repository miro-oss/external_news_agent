package com.example.be.domain.collection.service.command;

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
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 읽어 온 결과를 저장한다. <b>여기서만 트랜잭션을 연다.</b>
 *
 * <p>HTTP와 대기는 {@link CollectionExecutor}가 트랜잭션 밖에서 끝내고, 이 메서드는 DB 작업만 한다.
 * 짧게 유지해야 커넥션이 외부 I/O 시간만큼 묶이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionResultWriter {

    /** 소스에 정책이 없을 때 한 번에 저장할 최대 건수. */
    private static final int DEFAULT_MAX_ARTICLES_PER_RUN = 30;

    private final ArticleRepository articleRepository;
    private final ArticleVersionRepository articleVersionRepository;
    private final CollectionRunArticleRepository runArticleRepository;
    private final CollectionRunRepository runRepository;
    private final CollectionRunItemRepository runItemRepository;
    private final TopicRepository topicRepository;
    private final SourceRepository sourceRepository;

    /**
     * {@code scannedCount}는 <b>필터 전에 받은 건수</b>다. 키워드로 걸러진 기사도 "훑기는 했다".
     * 그래서 {@code skipped = scanned - new - updated}에는 중복과 필터 탈락이 함께 들어간다.
     */
    @Transactional
    public void write(Long runId,
                      Long itemId,
                      Long topicId,
                      Long sourceId,
                      CollectionOutcome outcome) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        CollectionRunItem item = runItemRepository.findById(itemId).orElseThrow();
        Topic topic = topicRepository.findById(topicId).orElseThrow();
        Source source = sourceRepository.findById(sourceId).orElseThrow();

        writeManaged(run, item, topic, source, outcome);
    }

    @Transactional
    public void write(CollectionRun run,
                      CollectionRunItem item,
                      Topic topic,
                      Source source,
                      CollectionOutcome outcome) {
        writeManaged(run, item, topic, source, outcome);
    }

    private void writeManaged(CollectionRun run,
                              CollectionRunItem item,
                              Topic topic,
                              Source source,
                              CollectionOutcome outcome) {
        outcome.robots().applyTo(source);

        if (!outcome.robots().allowed()) {
            item.markSkipped();
            run.addWarning(warning(source, CollectionRunWarning.CODE_ROBOTS_DISALLOWED,
                    "robots.txt가 수집을 막는다: " + outcome.robots().robotsTxtUrl()));
            return;
        }

        // 실패한 응답에는 검증자가 없다. 그대로 덮으면 503 한 번에 저장해 둔 ETag가 사라진다.
        if (outcome.validatorsUpdated()) {
            source.applyFetchState(outcome.etag(), outcome.lastModified(), LocalDateTime.now(ApiTimeZone.ZONE));
        }

        if (outcome.notModified()) {
            item.markSkipped();
            return;
        }

        if (!outcome.fetch().success()) {
            item.markFailed();
            run.addWarning(warning(source, outcome.fetch().failureCode(), outcome.fetch().failureMessage()));
            return;
        }

        List<CollectedArticle> collected = outcome.fetch().articles();
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
    }

    /**
     * 조합 하나가 죽어도 실행은 계속돼야 한다. 사유는 경고로 남아 화면에서 보인다.
     */
    @Transactional
    public void writeFailure(Long runId, Long itemId, Long sourceId, String message) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        CollectionRunItem item = runItemRepository.findById(itemId).orElseThrow();
        Source source = sourceRepository.findById(sourceId).orElseThrow();

        writeFailureManaged(run, item, source, message);
    }

    @Transactional
    public void writeFailure(CollectionRun run, CollectionRunItem item, Source source, String message) {
        writeFailureManaged(run, item, source, message);
    }

    private void writeFailureManaged(CollectionRun run, CollectionRunItem item, Source source, String message) {
        item.markFailed();
        run.addWarning(warning(source, CollectionRunWarning.CODE_FEED_UNREADABLE, message));
    }

    /**
     * 전문 추출 결과 1건을 반영한다. HTTP는 이미 끝났고 여기서는 DB만 만진다.
     */
    @Transactional
    public void applyFullText(Long articleId, FetchStatus fetchStatus, String body) {
        articleRepository.findById(articleId)
                .ifPresent(article -> article.applyFullText(body, fetchStatus, LocalDateTime.now(ApiTimeZone.ZONE)));
    }

    /**
     * 페이월로 막힌 기사를 소스별로 묶어 경고 하나로 남긴다. 기사마다 경고를 남기면 상세 화면이 도배된다.
     */
    @Transactional
    public void addFullTextBlockedWarning(Long runId, Long sourceId, int articleCount) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        Source source = sourceRepository.findById(sourceId).orElseThrow();

        run.addWarning(CollectionRunWarning.builder()
                .source(source)
                .code(CollectionRunWarning.CODE_FULLTEXT_BLOCKED)
                .message("페이월로 전문을 가져오지 못했습니다. 제목과 링크만 저장했습니다.")
                .articleCount(articleCount)
                .occurredAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
    }

    @Transactional
    public void finishRun(Long runId) {
        CollectionRun run = runRepository.findById(runId).orElseThrow();
        run.finish(LocalDateTime.now(ApiTimeZone.ZONE));
    }

    /**
     * 실행을 비정상 종료로 닫는다.
     *
     * <p>무조건 FAILED로 적으면 앞에서 성공한 조합이 묻힌다. 아직 안 끝난 조합만 실패로 닫고
     * 나머지는 저장된 결과 그대로 두면, finish()가 PARTIAL / FAILED를 정확히 계산한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(Long runId) {
        abortRun(runId, CollectionRunWarning.CODE_RUN_REJECTED,
                "수집 작업이 거절되어 실행을 시작하지 못했습니다.");
    }

    /**
     * 사유를 남기고 실행을 닫는다. 실행이 없으면 아무것도 하지 않고 {@code false}를 돌려준다.
     *
     * <p>경고를 같이 적는 이유는, 사유 없이 FAILED만 남으면 화면에서 "왜 실패했지"에 답할 수 없기 때문이다.
     * 소스와 무관한 실행 수준의 경고라 {@code source}는 null이다.
     */
    @Transactional
    public boolean abortRun(Long runId, String warningCode, String warningMessage) {
        return runRepository.findById(runId)
                .map(run -> {
                    run.addWarning(warning(null, warningCode, warningMessage));
                    run.abort(LocalDateTime.now(ApiTimeZone.ZONE));
                    return true;
                })
                .orElse(false);
    }

    private CollectionRunWarning warning(Source source, String code, String message) {
        return CollectionRunWarning.builder()
                .source(source)
                .code(code)
                .message(trim(message))
                .articleCount(0)
                .occurredAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build();
    }

    /**
     * 같은 실행·같은 조합에서 같은 URL이 두 번 오면 uq_run_article을 위반해 조합 전체가 실패한다.
     * 실제로 한 기사를 여러 섹션에 중복 노출하는 피드가 있다.
     */
    private List<CollectedArticle> dedupeByUrl(List<CollectedArticle> articles) {
        Map<String, CollectedArticle> byUrlHash = new LinkedHashMap<>();
        articles.forEach(article -> byUrlHash.putIfAbsent(ArticleHasher.urlHash(article.canonicalUrl()), article));
        return List.copyOf(byUrlHash.values());
    }

    /**
     * url_hash로 이미 있는 기사인지 보고 NEW / UPDATED / UNCHANGED를 정한다. 판정과 무관하게
     * 관측은 매번 남긴다 — 그래야 "이 실행에서 본 기사"를 나중에 복원할 수 있다.
     */
    private ChangeType save(CollectionRun run, Topic topic, Source source, CollectedArticle collected) {
        String urlHash = ArticleHasher.urlHash(collected.canonicalUrl());
        String contentHash = ArticleHasher.contentHash(collected.title(), collected.summary(), null);
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);

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
            article.applyUpdate(collected.title(), collected.summary(), article.getBody(), contentHash,
                    article.getFetchStatus(), run, now);
            changeType = ChangeType.UPDATED;
        }

        runArticleRepository.save(CollectionRunArticle.observe(run, article, topic, source, changeType, now));
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
                // 전문은 F6의 본문 추출이 따로 받는다. RSS의 description은 잘린 요약이다.
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

    private String trim(String message) {
        if (message == null) {
            return null;
        }

        return message.length() > CollectionRunWarning.MAX_MESSAGE_LENGTH
                ? message.substring(0, CollectionRunWarning.MAX_MESSAGE_LENGTH)
                : message;
    }
}
