package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingAnalysisBullet;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.SensitivityCalculator;
import com.example.be.domain.articles.service.ArticleQueryService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private CollectionRunArticleRepository runArticleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private CollectionRun run;
    private Article article;
    private Topic topic;
    private Source source;

    @BeforeEach
    void setUp() {
        topic = topicRepository.save(Topic.builder()
                .name("M4 finding 통합테스트 " + UUID.randomUUID())
                .queryText("HBM")
                .requiredKeywords(List.of())
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        source = sourceRepository.save(Source.builder()
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
        assertTrue(versions.contains("6"));
        assertTrue(versions.contains("10"));
        assertTrue(versions.contains("11"));
        assertTrue(versions.contains("12"));
        assertTrue(versions.contains("16"));
        assertTrue(versions.contains("17"));

        Finding saved = findingRepository.save(finding());
        flushAndClear();

        Finding found = findingRepository.findById(saved.getId()).orElseThrow();
        assertEquals("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.", found.getSummary());
        assertTrue(found.getKeyPoints().isEmpty());
        assertEquals(List.of(0), found.getEffectiveKeyPoints().getFirst().evidence());
        assertEquals("The United States tightened export controls.", found.getSections().get(0).text());
        assertEquals(SensitivityLevel.HIGH,
                SensitivityCalculator.defaults().level(found.getSensitivity().getScore()));
        assertEquals(AnalysisSource.LLM, found.getAnalysisSource());
        assertEquals("핵심", found.getAnalysisSections().getFirst().heading());
        assertEquals(BigDecimal.ONE,
                found.getAnalysisSections().getFirst().bullets().getFirst().confidence());
        assertEquals("발화 주체와 함께 확인됩니다.",
                found.getEffectiveKeyPoints().getFirst().groundingReason());
        assertEquals("OPINION", found.getEffectiveKeyPoints().getFirst().claimType());
        assertEquals("미국 정부", found.getEffectiveKeyPoints().getFirst().attributedTo());
        assertEquals(List.of("HBM4"), found.getEntities().products());
        assertEquals("gemini", found.getLlmProvider());
        assertEquals("c".repeat(64), found.getAnalysisInputHash());
        assertEquals(120L, found.getInputTokens());
        assertTrue(found.isInputTruncated());
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
    void findsLlmCacheSourceOnlyForMatchingAnalysisInputHash() {
        Finding saved = findingRepository.save(finding());
        flushAndClear();

        List<Finding> cached = findingRepository.findReusableSources(
                List.of(article.getId()),
                AnalysisSource.LLM,
                List.of("c".repeat(64)),
                "analyze.ko.v1",
                "gemini",
                "gemini-2.5-flash");

        assertEquals(List.of(saved.getId()), cached.stream().map(Finding::getId).toList());
        assertTrue(findingRepository.findReusableSources(
                List.of(article.getId()), AnalysisSource.LLM, List.of("d".repeat(64)),
                "analyze.ko.v1", "gemini", "gemini-2.5-flash").isEmpty());
        assertTrue(findingRepository.findReusableSources(
                List.of(article.getId()), AnalysisSource.STUB, List.of("c".repeat(64)),
                "analyze.ko.v1", "gemini", "gemini-2.5-flash").isEmpty());
        assertTrue(findingRepository.findReusableSources(
                List.of(article.getId()), AnalysisSource.LLM, List.of("c".repeat(64)),
                "analyze.ko.v2", "gemini", "gemini-2.5-flash").isEmpty());
    }

    @Test
    void filtersAndReturnsKoreanSummaryThroughArticleApiService() {
        findingRepository.save(finding());
        flushAndClear();

        var response = articleQueryService.getArticles(
                run.getId(), null, null, "NEW", "important", "high", "정책", "en",
                null, null, null, null, "SENSITIVITY_DESC", 0, 20);

        assertEquals(1, response.getTotalElements());
        assertEquals("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.",
                response.getContent().get(0).getSummary());
        assertEquals("high", response.getContent().get(0).getSensitivity().level());
    }

    @Test
    void filtersByAudienceAndMinimumRelevanceThroughOracleJson() {
        findingRepository.save(finding());
        flushAndClear();

        var matched = articleQueryService.getArticles(
                run.getId(), null, null, null, null, null, null, null,
                "EQUIPMENT_MAKER", "medium", null, null, "PUBLISHED_DESC", 0, 20);
        var excluded = articleQueryService.getArticles(
                run.getId(), null, null, null, null, null, null, null,
                "IT_INFRA", "low", null, null, "PUBLISHED_DESC", 0, 20);

        assertEquals(1, matched.getTotalElements());
        assertEquals("EQUIPMENT_MAKER",
                matched.getContent().getFirst().getPerspectiveTags().get(1).getAudience());
        assertEquals(0, excluded.getTotalElements());
    }

    @Test
    void keepsAnalyzedSentenceSsotWhenArticleBodyChangesLater() {
        findingRepository.save(finding());
        flushAndClear();

        Article current = articleRepository.findById(article.getId()).orElseThrow();
        current.applyFullText("Fresh full text first. Fresh full text second.",
                FetchStatus.FULLTEXT, LocalDateTime.now());
        flushAndClear();

        var detail = articleQueryService.getArticle(article.getId(), null);

        assertEquals("The United States tightened export controls.",
                detail.getSentences().getFirst().getText());
        assertEquals("The United States tightened export controls.", detail.getBodyText());
        assertEquals(1, detail.getSentences().size());
        assertEquals("미국 정부는 수출 통제를 강화해야 한다는 입장이다.",
                detail.getAnalysis().getKeyPoints().getFirst().getText());
    }

    @Test
    void returnsCurrentBodyWithoutSentenceIndexesBeforeAnalysis() {
        var detail = articleQueryService.getArticle(article.getId(), null);

        assertEquals(article.getBody(), detail.getBodyText());
        assertTrue(detail.getSentences().isEmpty());
        assertNull(detail.getAnalysis());
    }

    @Test
    void topicAndSourceFiltersUseArticleObservationHistory() {
        runArticleRepository.save(CollectionRunArticle.observe(
                run, article, topic, source, ChangeType.NEW, LocalDateTime.now().minusMinutes(1)));
        Topic otherTopic = topicRepository.save(Topic.builder()
                .name("다른 주제 " + UUID.randomUUID())
                .queryText("AI")
                .requiredKeywords(List.of())
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        Source otherSource = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("다른 소스 " + UUID.randomUUID())
                .urlTemplate("https://example.com/other/" + UUID.randomUUID())
                .language("en")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .active(true)
                .build());
        CollectionRun latestRun = runRepository.save(CollectionRun.builder()
                .status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(LocalDateTime.now().minusSeconds(30))
                .finishedAt(LocalDateTime.now())
                .scannedCount(1)
                .newCount(0)
                .updatedCount(1)
                .skippedCount(0)
                .build());
        runArticleRepository.save(CollectionRunArticle.observe(
                latestRun, article, otherTopic, otherSource, ChangeType.UPDATED, LocalDateTime.now()));
        findingRepository.save(finding(latestRun));
        flushAndClear();

        var response = articleQueryService.getArticles(
                null, topic.getId(), source.getId(), null, null, null, null, null,
                null, null, null, null, "PUBLISHED_DESC", 0, 20);

        assertEquals(1, response.getTotalElements());
        assertEquals(article.getId(), response.getContent().get(0).getId());
    }

    private Finding finding() {
        return finding(run);
    }

    private Finding finding(CollectionRun findingRun) {
        return Finding.builder()
                .run(findingRun)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.")
                .keyPoints(List.of())
                .intent("정책 변화 보도")
                .sentiment(Sentiment.NEGATIVE)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT)
                .category("정책")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "The United States tightened export controls.")))
                .analysisSections(List.of(new FindingAnalysisSection(
                        "핵심",
                        List.of(new FindingAnalysisBullet(
                                "미국 정부는 수출 통제를 강화해야 한다는 입장이다.",
                                List.of(0),
                                "grounded",
                                BigDecimal.ONE,
                                "발화 주체와 함께 확인됩니다.",
                                "OPINION",
                                "미국 정부")))))
                .entities(new FindingEntities(List.of("미국 정부"), List.of("HBM4"), List.of()))
                .perspectiveTags(List.of(
                        new FindingPerspectiveTag(
                                Audience.CHIP_MAKER,
                                AudienceRelevance.HIGH,
                                "반도체 제조사의 수출 통제 대응이 필요하다.",
                                List.of(0)),
                        new FindingPerspectiveTag(
                                Audience.EQUIPMENT_MAKER,
                                AudienceRelevance.MEDIUM,
                                "장비 수출 허가 범위가 바뀐다.",
                                List.of(0)),
                        new FindingPerspectiveTag(
                                Audience.MARKET_INVESTOR,
                                AudienceRelevance.LOW,
                                "정책 변수를 관찰해야 한다.",
                                List.of(0)),
                        new FindingPerspectiveTag(
                                Audience.IT_INFRA,
                                AudienceRelevance.NONE,
                                null,
                                List.of())))
                .promptVersion("analyze.ko.v1")
                .llmProvider("gemini")
                .llmModel("gemini-2.5-flash")
                .inputTokens(120L)
                .outputTokens(30L)
                .costUsd(new BigDecimal("0.001"))
                .credits(BigDecimal.ZERO)
                .analysisInputHash("c".repeat(64))
                .inputTruncated(true)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
