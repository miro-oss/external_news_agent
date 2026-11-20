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
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicTrendJdbcRepositoryIntegrationTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 9, 15);

    @Autowired private TopicTrendJdbcRepository repository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private CollectionRunRepository runRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private CollectionRunArticleRepository observationRepository;
    @Autowired private ArticleBodyStorage bodyStorage;
    @Autowired private EntityManager entityManager;

    @Test
    void countsIndividualArticlesWithoutIssuesOrLlmFindingsAndKeepsEmptyKeywordDenominator() {
        Topic topic = topic();
        Source source = source();
        article(topic, source, NOW.minusDays(8), "마이크론 HBM4 공급", FetchStatus.FULLTEXT, "HBM4 공급 확대 기사 본문");
        article(topic, source, NOW.minusDays(2), "마이크론 HBM4 출시", FetchStatus.FULLTEXT, "HBM4 출시 기사 본문");
        article(topic, source, NOW.minusDays(1), "마이크론 HBM4 생산", FetchStatus.FULLTEXT, "HBM4 생산 기사 본문");
        article(topic, source, NOW.minusHours(1), "업계의 새로운 소식", FetchStatus.FULLTEXT, "별도 키워드 없는 기사 본문");
        flushAndClear();

        var result = repository.findSnapshots(List.of(topic.getId()), NOW).get(topic.getId());
        var trend = result.surgeKeywords().stream().filter(value -> value.keyword().equals("HBM4")).findFirst().orElseThrow();
        assertEquals(2, trend.articleCount());
        assertEquals(1, trend.previousArticleCount());
        assertEquals(1, trend.deltaArticleCount());
        var related = result.relatedKeywords().stream().filter(value -> value.keyword().equals("마이크론")).findFirst().orElseThrow();
        assertEquals(2, related.articleCount());
        assertEquals(new BigDecimal("66.67"), related.sharePercent());
    }

    @Test
    void deduplicatesRepeatedObservationsPerWindowAndSeparatesSharedArticleTopics() {
        Topic first = topic();
        Topic second = topic();
        Source source = source();
        Article shared = article(first, source, NOW.minusDays(8), "HBM4 공급", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        observe(first, source, shared, NOW.minusDays(2), ChangeType.UPDATED);
        observe(first, source, shared, NOW.minusDays(1), ChangeType.UPDATED);
        observe(second, source, shared, NOW.minusDays(1), ChangeType.UPDATED);
        article(first, source, NOW.minusDays(1), "HBM4 생산", FetchStatus.FULLTEXT, "HBM4 생산 기사 본문");
        article(second, source, NOW.minusHours(2), "DDR5 생산", FetchStatus.FULLTEXT, "DDR5 기사 본문");
        Article unchanged = article(second, source, NOW.minusDays(30), "HBM4 과거 소식", FetchStatus.FULLTEXT, "HBM4 과거 본문");
        observe(first, source, unchanged, NOW.minusHours(2), ChangeType.UNCHANGED);
        flushAndClear();

        var result = repository.findSnapshots(List.of(first.getId(), second.getId()), NOW);
        var trend = result.get(first.getId()).surgeKeywords().getFirst();
        assertEquals("HBM4", trend.keyword());
        assertEquals(2, trend.articleCount());
        assertEquals(1, trend.previousArticleCount());
        assertEquals(new BigDecimal("100.00"), result.get(first.getId()).relatedKeywords().getFirst().sharePercent());
        assertTrue(result.get(second.getId()).relatedKeywords().isEmpty());
    }

    @Test
    void appliesExactTimeBoundariesAndExcludesMetadataAndBlankBodyArticles() {
        Topic topic = topic();
        Source source = source();
        article(topic, source, NOW.minusDays(14), "HBM4 이전 경계", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW.minusDays(7), "HBM4 최근 경계", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW.minusSeconds(1), "HBM4 현재 직전", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW.minusDays(14).minusSeconds(1), "HBM4 기간 밖", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW, "HBM4 현재 경계", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW.plusHours(1), "HBM4 미래", FetchStatus.FULLTEXT, "HBM4 기사 본문");
        article(topic, source, NOW.minusHours(1), "HBM4 메타데이터", FetchStatus.METADATA_ONLY, null);
        article(topic, source, NOW.minusHours(1), "HBM4 공백 본문", FetchStatus.FULLTEXT, " \n\t ");
        flushAndClear();

        var result = repository.findSnapshots(List.of(topic.getId()), NOW).get(topic.getId());
        assertEquals(2, result.surgeKeywords().getFirst().articleCount());
        assertEquals(1, result.surgeKeywords().getFirst().previousArticleCount());
        assertEquals(new BigDecimal("100.00"), result.relatedKeywords().getFirst().sharePercent());
    }

    @Test
    void includesOrganizationsMentionedOnlyInArticleBody() {
        Topic topic = topic();
        Source source = source();
        article(topic, source, NOW.minusDays(2), "메모리 공급 소식", FetchStatus.FULLTEXT,
                "마이크론은 생산 시설을 확대했다. 마이크론의 공급 증가를 확인했다.");
        article(topic, source, NOW.minusDays(1), "MICRON 메모리 투자 소식", FetchStatus.FULLTEXT,
                "Micron increased manufacturing capacity.");
        flushAndClear();

        var result = repository.findSnapshots(List.of(topic.getId()), NOW).get(topic.getId());
        var trend = result.surgeKeywords().stream().filter(value -> value.keyword().equals("마이크론")).findFirst().orElseThrow();
        assertEquals(2, trend.articleCount());
        assertEquals(0, trend.previousArticleCount());
        assertEquals(List.of("마이크론"), result.relatedKeywords().stream().map(TopicTrendJdbcRepository.TopicRelatedKeyword::keyword).toList());
        assertEquals(new BigDecimal("100.00"), result.relatedKeywords().getFirst().sharePercent());
    }

    private Topic topic() {
        return topicRepository.save(Topic.builder().name("기사 추세 테스트 " + UUID.randomUUID())
                .queryText("반도체").requiredKeywords(List.of()).optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(100).intervalMinutes(60).active(true).build());
    }

    private Source source() {
        return sourceRepository.save(Source.builder().sourceKind(Source.KIND_FEED).name("기사 추세 소스")
                .urlTemplate("https://example.com/" + UUID.randomUUID()).language("ko").active(true).build());
    }

    private Article article(Topic topic, Source source, LocalDateTime at, String title, FetchStatus status, String body) {
        String key = UUID.randomUUID().toString().replace("-", "");
        Article article = articleRepository.save(Article.builder().topic(topic).source(source)
                .urlHash(key.repeat(2)).canonicalUrl("https://example.com/" + key).title(title)
                .summary(title).storedBody(bodyStorage.intern(body)).language("ko").fetchStatus(status)
                .collectedAt(at).build());
        observe(topic, source, article, at, ChangeType.NEW);
        return article;
    }

    private void observe(Topic topic, Source source, Article article, LocalDateTime at, ChangeType changeType) {
        CollectionRun run = runRepository.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).idempotencyKey("trend-" + UUID.randomUUID())
                .forceRefresh(false).startedAt(at).finishedAt(at.plusMinutes(1)).build());
        observationRepository.save(CollectionRunArticle.observe(run, article, topic, source, changeType, at));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
