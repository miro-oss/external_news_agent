package com.example.be.domain.topics.repository;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicTrendJdbcRepositoryIntegrationTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 12, 0);

    @Autowired
    private TopicTrendJdbcRepository repository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CollectionRunArticleRepository collectionRunArticleRepository;

    @Autowired
    private NewsIssueRepository newsIssueRepository;

    @Autowired
    private IssueArticleRepository issueArticleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsWeeklySurgeAndRelatedKeywordsFromIssueEntities() {
        Topic topic = topicRepository.save(Topic.builder()
                .name("HBM 트렌드 통합테스트 " + UUID.randomUUID())
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .batchSize(100)
                .intervalMinutes(60)
                .active(true)
                .build());
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("트렌드 통합테스트 소스")
                .urlTemplate("https://example.com/trend-" + UUID.randomUUID())
                .language("ko")
                .active(true)
                .build());

        CollectionRun previousRun = run("trend-prev", NOW.minusDays(8));
        CollectionRun recentRun1 = run("trend-recent-1", NOW.minusDays(2));
        CollectionRun recentRun2 = run("trend-recent-2", NOW.minusDays(1));
        CollectionRun recentRunWithoutEntities = run("trend-recent-empty", NOW.minusDays(3));

        observeIssue(topic, source, previousRun, NOW.minusDays(8),
                List.of("HBM", "HBM4", "마이크론"), "old");
        observeIssue(topic, source, recentRun1, NOW.minusDays(2),
                List.of("HBM", "HBM4", "마이크론"), "recent-a");
        observeIssue(topic, source, recentRun2, NOW.minusDays(1),
                List.of("HBM", "HBM4", "마이크론", "패키징"), "recent-b");
        observeIssue(topic, source, recentRunWithoutEntities, NOW.minusDays(3),
                List.of(), "recent-empty");

        flushAndClear();

        TopicTrendJdbcRepository.TopicTrendSnapshot snapshot =
                repository.findSnapshots(List.of(topic.getId()), NOW).get(topic.getId());

        assertNotNull(snapshot);
        assertTrue(snapshot.surgeKeywords().stream().anyMatch(keyword ->
                keyword.keyword().equals("HBM4")
                        && keyword.issueCount() == 2
                        && keyword.previousIssueCount() == 1
                        && keyword.deltaIssueCount() == 1
                        && keyword.zScore() != null
                        && keyword.zScore().compareTo(BigDecimal.ZERO) > 0));
        assertTrue(snapshot.relatedKeywords().stream().anyMatch(keyword ->
                keyword.keyword().equals("마이크론")
                        && keyword.issueCount() == 2
                        && keyword.sharePercent().compareTo(new BigDecimal("66.67")) == 0));
    }

    private CollectionRun run(String key, LocalDateTime startedAt) {
        return runRepository.save(CollectionRun.builder()
                .status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey(key + "-" + UUID.randomUUID())
                .forceRefresh(false)
                .startedAt(startedAt)
                .finishedAt(startedAt.plusMinutes(5))
                .build());
    }

    private void observeIssue(Topic topic,
                              Source source,
                              CollectionRun run,
                              LocalDateTime observedAt,
                              List<String> entities,
                              String suffix) {
        Article article = articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(urlHash(suffix))
                .canonicalUrl("https://example.com/" + suffix)
                .title("트렌드 기사 " + suffix)
                .summary("HBM4와 마이크론 동향 " + suffix)
                .language("ko")
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(observedAt)
                .build());
        NewsIssue issue = newsIssueRepository.save(NewsIssue.builder()
                .title("트렌드 이슈 " + suffix)
                .summary("트렌드 요약 " + suffix)
                .status(IssueStatus.EMERGING)
                .importanceScore(new BigDecimal("50.00"))
                .sensitivityScore(new BigDecimal("50.00"))
                .firstSeenAt(toOffset(observedAt))
                .lastSeenAt(toOffset(observedAt))
                .articleCount(1)
                .publisherCount(1)
                .independentContentCount(1)
                .topic(topic)
                .entities(entities)
                .build());
        issueArticleRepository.save(IssueArticle.builder()
                .issue(issue)
                .article(article)
                .role(IssueArticleRole.REPRESENTATIVE)
                .stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE)
                .stanceConfidence(BigDecimal.ONE)
                .joinedAt(observedAt)
                .build());
        collectionRunArticleRepository.save(CollectionRunArticle.observe(
                run,
                article,
                topic,
                source,
                ChangeType.NEW,
                observedAt
        ));
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value.atOffset(ZoneOffset.ofHours(9));
    }

    private String urlHash(String suffix) {
        return (suffix.replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
                .replace("-", "")
                .substring(0, 32)
                .repeat(2)
                .substring(0, 64);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
