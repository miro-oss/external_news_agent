package com.example.be.domain.analysis.repository;

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
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ArticleAnalysisPipeline}은 클래스 주석이 밝힌 대로 <b>트랜잭션 밖에서</b> 돈다.
 * {@code spring.jpa.open-in-view: false}라 세션도 없다. 그래서 분석 대상 조회 쿼리가
 * 파이프라인이 실제로 건드리는 연관을 전부 fetch join 해야 한다.
 *
 * <p>이 테스트는 그 fetch 계획을 <b>세션을 닫은 뒤</b> 검증한다. 기존 파이프라인 단위 테스트는
 * 리포지터리를 mock 하고 엔티티를 직접 build 해서 프록시가 아예 생기지 않았고,
 * 그래서 issue #115의 {@code LazyInitializationException}을 잡지 못했다.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class AnalysisTargetFetchPlanIntegrationTests {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private CollectionRunArticleRepository runArticleRepository;

    @Autowired
    private NewsIssueRepository issueRepository;

    @Autowired
    private IssueArticleRepository issueArticleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 같은 URL이 두 주제에서 관측되면 {@code observation.topic}과 {@code article.topic}이 갈린다.
     * 그때 {@code article.topic}만 fetch 하면 {@code observation.topic}은 프록시로 남는다.
     */
    @Test
    void representativeAnalysisTargetsInitializeObservationTopicOutsideSession() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        try {
            List<CollectionRunArticle> targets = transactionTemplate.execute(status ->
                    runArticleRepository.findRepresentativeAnalysisTargetsByRunId(fixture.runId()));

            assertEquals(1, targets.size());
            CollectionRunArticle observation = targets.getFirst();
            // 관측 주제는 기사 주제와 다른 행이다. 프록시로 남아 있으면 파이프라인이 여기서 터진다.
            assertTrue(Hibernate.isInitialized(observation.getTopic()),
                    "observation.topic이 fetch join 되지 않았다");
            assertTrue(Hibernate.isInitialized(observation.getArticle()));
            assertDoesNotThrow(() -> observation.getTopic().getName());
            assertEquals(fixture.observationTopicId(), observation.getTopic().getId());
            assertFalse(fixture.observationTopicId().equals(fixture.articleTopicId()),
                    "픽스처가 관측 주제와 기사 주제를 갈라 놓지 못하면 회귀를 못 잡는다");
        } finally {
            transactionTemplate.executeWithoutResult(status -> deleteFixture(fixture));
        }
    }

    /**
     * {@code IssueArticle.issue}는 {@code optional = false} LAZY다. 프록시가 절대 null이 아니라
     * {@code membership.getIssue().getTopic()}이 세션 밖에서 바로 터진다.
     */
    @Test
    void representativesForRunInitializeIssueAndItsTopicOutsideSession() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        try {
            List<IssueArticle> representatives = transactionTemplate.execute(status ->
                    issueArticleRepository.findRepresentativesForRun(fixture.runId()));

            assertEquals(1, representatives.size());
            IssueArticle membership = representatives.getFirst();
            assertTrue(Hibernate.isInitialized(membership.getIssue()),
                    "representative.issue가 fetch join 되지 않았다");
            assertDoesNotThrow(() -> membership.getIssue().getTopic().getName());
            assertEquals(fixture.issueTopicId(), membership.getIssue().getTopic().getId());
            assertDoesNotThrow(() -> membership.getArticle().getTitle());
        } finally {
            transactionTemplate.executeWithoutResult(status -> deleteFixture(fixture));
        }
    }

    private Fixture createFixture() {
        long stamp = System.nanoTime();
        // 이슈 주제 = 관측 주제. 기사 주제만 다른 주제로 둬서 두 연관이 갈리게 만든다.
        Topic observationTopic = topicRepository.save(Topic.builder()
                .name("fetch-plan 관측주제 " + stamp)
                .batchSize(10).intervalMinutes(60).active(true).build());
        Topic articleTopic = topicRepository.save(Topic.builder()
                .name("fetch-plan 기사주제 " + stamp)
                .batchSize(10).intervalMinutes(60).active(true).build());
        Source source = sourceRepository.save(Source.builder()
                .sourceKind("FEED")
                .name("fetch-plan 소스 " + stamp)
                .urlTemplate("https://example.test/fetch-plan/" + stamp + ".xml")
                .active(true)
                .build());
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        OffsetDateTime seenAt = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        Article article = articleRepository.save(Article.builder()
                .topic(articleTopic)
                .source(source)
                .urlHash("fetchplan" + stamp)
                .canonicalUrl("https://example.test/fetch-plan/" + stamp)
                .title("HBM4 증설")
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .collectedAt(now)
                .build());
        CollectionRun run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(now)
                .build());
        CollectionRunArticle observation = runArticleRepository.save(CollectionRunArticle.builder()
                .run(run)
                .article(article)
                .topic(observationTopic)
                .source(source)
                .changeType(ChangeType.NEW)
                .observedAt(now)
                .build());
        NewsIssue issue = issueRepository.save(NewsIssue.builder()
                .title("HBM4 증설")
                .status(IssueStatus.EMERGING)
                .firstSeenAt(seenAt)
                .lastSeenAt(seenAt)
                .articleCount(1)
                .publisherCount(1)
                .independentContentCount(1)
                .topic(observationTopic)
                .entities(List.of("HBM4"))
                .build());
        IssueArticle membership = issueArticleRepository.save(IssueArticle.builder()
                .issue(issue)
                .article(article)
                .role(IssueArticleRole.REPRESENTATIVE)
                .stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE)
                .stanceConfidence(BigDecimal.ONE)
                .joinedAt(now)
                .build());
        return new Fixture(run.getId(), observation.getId(), membership.getId(), issue.getId(),
                article.getId(), source.getId(), observationTopic.getId(), articleTopic.getId(),
                observationTopic.getId());
    }

    private void deleteFixture(Fixture fixture) {
        issueArticleRepository.deleteById(fixture.membershipId());
        issueRepository.deleteById(fixture.issueId());
        runArticleRepository.deleteById(fixture.observationId());
        runRepository.deleteById(fixture.runId());
        articleRepository.deleteById(fixture.articleId());
        sourceRepository.deleteById(fixture.sourceId());
        topicRepository.deleteById(fixture.observationTopicId());
        topicRepository.deleteById(fixture.articleTopicId());
    }

    private record Fixture(Long runId,
                           Long observationId,
                           Long membershipId,
                           Long issueId,
                           Long articleId,
                           Long sourceId,
                           Long observationTopicId,
                           Long articleTopicId,
                           Long issueTopicId) {
    }
}
