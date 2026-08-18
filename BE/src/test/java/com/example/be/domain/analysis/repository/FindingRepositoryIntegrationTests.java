package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.articles.service.ArticleQueryService;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class FindingRepositoryIntegrationTests {

    @Autowired
    private FindingRepository findingRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ArticleQueryService articleQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private CollectionRun run;
    private Article article;

    @BeforeEach
    void setUp() {
        Topic topic = topicRepository.save(Topic.builder()
                .name("M4 finding 통합테스트 " + UUID.randomUUID())
                .queryText("HBM")
                .requiredKeywords(List.of())
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("M4 finding 소스 " + UUID.randomUUID())
                .urlTemplate("https://example.com/" + UUID.randomUUID())
                .country("US")
                .language("en")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .active(true)
                .build());
        run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(LocalDateTime.now().minusMinutes(1))
                .finishedAt(LocalDateTime.now())
                .scannedCount(1)
                .newCount(1)
                .updatedCount(0)
                .skippedCount(0)
                .build());
        article = articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash("a".repeat(64))
                .canonicalUrl("https://example.com/article/" + UUID.randomUUID())
                .title("US tightens export controls on advanced chipmaking tools")
                .summary("New restrictions target semiconductor equipment exports.")
                .body("The United States tightened export controls. Supply chain risks increased.")
                .contentHash("b".repeat(64))
                .language("en")
                .sourceName("Reuters")
                .publishedAt(OffsetDateTime.now().minusHours(1))
                .fetchStatus(FetchStatus.FULLTEXT)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(LocalDateTime.now())
                .build());
    }

    @Test
    void appliesV5AndRoundTripsJsonAnalysis() {
        List<String> versions = jdbcTemplate.queryForList("""
                SELECT "version" FROM "flyway_schema_history" WHERE "success" = 1
                """, String.class);
        assertTrue(versions.contains("5"));

        Finding saved = findingRepository.save(finding());
        flushAndClear();

        Finding found = findingRepository.findById(saved.getId()).orElseThrow();
        assertEquals("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.", found.getSummary());
        assertEquals(List.of(0), found.getKeyPoints().get(0).evidence());
        assertEquals("The United States tightened export controls.", found.getSections().get(0).text());
        assertEquals(RiskLevel.HIGH, found.getRiskLevel());
    }

    @Test
    void rejectsDuplicateFindingForSameRunAndArticle() {
        findingRepository.save(finding());
        flushAndClear();

        assertThrows(DataIntegrityViolationException.class, () -> {
            findingRepository.save(finding());
            entityManager.flush();
        });
    }

    @Test
    void filtersAndReturnsKoreanSummaryThroughArticleApiService() {
        findingRepository.save(finding());
        flushAndClear();

        var response = articleQueryService.getArticles(
                null, null, null, "NEW", "important", "high", "정책", "en",
                null, null, "RISK_DESC", 0, 20);

        assertEquals(1, response.getTotalElements());
        assertEquals("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.",
                response.getContent().get(0).getSummary());
        assertEquals("high", response.getContent().get(0).getRiskLevel());
    }

    private Finding finding() {
        return Finding.builder()
                .run(run)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.")
                .keyPoints(List.of(new FindingKeyPoint(
                        "미국이 첨단 반도체 장비 수출 통제를 강화했다.", List.of(0), "grounded")))
                .intent("정책 변화 보도")
                .sentiment(Sentiment.NEGATIVE)
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("정책")
                .sections(List.of(new FindingSection(0, "The United States tightened export controls.")))
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
