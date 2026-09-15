package com.example.be.domain.collection.cluster;

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
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class IssueBodyEligibilityIntegrationTests {

    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleBodyStorage bodyStorage;
    @Autowired private CollectionRunRepository runRepository;
    @Autowired private CollectionRunArticleRepository observationRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private NewsIssueRepository issueRepository;
    @Autowired private IssueArticleRepository membershipRepository;
    @Autowired private NewsWatchRepository watchRepository;
    @Autowired private IssueClusteringLoader loader;
    @Autowired private IssueClusterer clusterer;
    @Autowired private IssueClusterWriter writer;
    @Autowired private EntityManager entityManager;

    private Topic topic;
    private Source source;
    private CollectionRun run;
    private final OffsetDateTime now = OffsetDateTime.now(ApiTimeZone.ZONE).withNano(0);

    @BeforeEach
    void setUp() {
        topic = topicRepository.save(Topic.builder().name("본문 근거 " + UUID.randomUUID())
                .queryText("HBM4").requiredKeywords(List.of("HBM4"))
                .optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(10).intervalMinutes(60).active(true).build());
        source = sourceRepository.save(Source.builder().sourceKind(Source.KIND_FEED)
                .name("본문 근거 소스").urlTemplate("https://example.com/" + UUID.randomUUID())
                .country("KR").language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN).active(true).build());
        run = runRepository.save(CollectionRun.builder().status(RunStatus.PENDING)
                .triggerType(TriggerType.MANUAL).forceRefresh(false).startedAt(now.toLocalDateTime()).build());
    }

    @Test
    void candidateKeepsCollectionRecordAndJoinsOnlyAfterItsOwnBodyIsFetched() {
        Article candidate = article("[속보] 삼성전자 HBM4 양산 시작", null);
        observe(candidate);
        flushAndClear();

        ClusterPlan pending = clusterer.cluster(loader.load(run.getId()));
        assertTrue(pending.issues().isEmpty());
        writer.write(pending);
        assertTrue(membershipRepository.findByArticleIds(List.of(candidate.getId())).isEmpty());
        assertEquals(1, observationRepository.findByRunIdOrderByIdAsc(run.getId()).size());
        assertTrue(articleRepository.existsById(candidate.getId()));

        Article fetched = articleRepository.findById(candidate.getId()).orElseThrow();
        fetched.applyStoredFullText(bodyStorage.intern("삼성전자가 오늘 HBM4 양산을 시작했다고 밝혔다."),
                FetchStatus.FULLTEXT, now.toLocalDateTime());
        flushAndClear();
        ClusterPlan confirmed = clusterer.cluster(loader.load(run.getId()));
        assertEquals(1, confirmed.issues().size());
        assertTrue(confirmed.contentGroups().isEmpty());
        writer.write(confirmed);
        flushAndClear();

        List<IssueArticle> memberships = membershipRepository.findByArticleIds(List.of(candidate.getId()));
        assertEquals(1, memberships.size());
        assertTrue(memberships.getFirst().getArticle().hasFullText());
        assertEquals(1, memberships.getFirst().getIssue().getArticleCount());
        assertEquals(1, observationRepository.findByRunIdOrderByIdAsc(run.getId()).size());
        assertTrue(watchRepository.findByIssueIdAndWatchType(
                memberships.getFirst().getIssue().getId(), WatchType.BREAKING).isPresent());
    }

    @Test
    void legacyMetadataIssueAndWatchRemainUntouchedWhenFullTextFollowUpArrives() {
        Article metadata = article("[속보] 삼성전자 HBM4 양산 시작", null);
        NewsIssue legacy = issueRepository.save(NewsIssue.builder().title(metadata.getTitle())
                .topic(topic).status(IssueStatus.EMERGING).firstSeenAt(now).lastSeenAt(now)
                .articleCount(1).publisherCount(1).independentContentCount(1).build());
        membershipRepository.save(IssueArticle.builder().issue(legacy).article(metadata)
                .role(IssueArticleRole.REPRESENTATIVE).stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE).stanceConfidence(BigDecimal.ONE)
                .joinedAt(now.toLocalDateTime()).build());
        LocalDateTime expires = now.plusHours(48).toLocalDateTime();
        NewsWatch watch = watchRepository.save(NewsWatch.builder().issue(legacy).watchType(WatchType.BREAKING)
                .active(true).expiresAt(expires).build());
        observe(metadata);
        Article followUp = article("삼성전자 HBM4 양산 시작", "삼성전자가 HBM4 양산을 시작했다고 발표했다.");
        observe(followUp);
        flushAndClear();

        ClusterPlan plan = clusterer.cluster(loader.load(run.getId()));
        assertEquals(1, plan.issues().size());
        assertEquals(List.of(followUp.getId()), plan.issues().getFirst().articleIds());
        writer.write(plan);
        flushAndClear();

        IssueArticle original = membershipRepository.findByArticleIds(List.of(metadata.getId())).getFirst();
        assertEquals(legacy.getId(), original.getIssue().getId());
        assertEquals(IssueArticleRole.REPRESENTATIVE, original.getRole());
        assertEquals(1, original.getIssue().getArticleCount());
        assertEquals(metadata.getTitle(), original.getIssue().getTitle());
        NewsWatch preserved = watchRepository.findById(watch.getId()).orElseThrow();
        assertTrue(preserved.isActive());
        assertEquals(expires, preserved.getExpiresAt());
        assertEquals(legacy.getId(), preserved.getIssue().getId());
        assertEquals(2, observationRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    private Article article(String title, String body) {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        return articleRepository.save(Article.builder().topic(topic).source(source).title(title)
                .summary(title).urlHash(hash).canonicalUrl("https://example.com/article/" + hash)
                .storedBody(bodyStorage.intern(body)).fetchStatus(body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT)
                .sourceName("통합테스트 매체").language("ko").contentHash(hash)
                .publishedAt(now).collectedAt(now.toLocalDateTime()).firstSeenRun(run).lastSeenRun(run).build());
    }

    private void observe(Article article) {
        observationRepository.save(CollectionRunArticle.observe(
                run, article, topic, source, ChangeType.NEW, now.toLocalDateTime()));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
