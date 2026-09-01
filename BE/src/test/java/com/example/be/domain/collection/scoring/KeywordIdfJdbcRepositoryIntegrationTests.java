package com.example.be.domain.collection.scoring;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.scoring.KeywordIdfJdbcRepository.CachedKeywordIdf;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class KeywordIdfJdbcRepositoryIntegrationTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);
    private static final String LANGUAGE = "x1";
    private static final String KEYWORD = "raresummarytoken";

    @Autowired
    private KeywordIdfJdbcRepository keywordIdfRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Test
    void countsCaseInsensitiveClobMatchesAndUpsertsCacheRow() {
        Topic topic = topicRepository.save(Topic.builder()
                .name("IDF 통합테스트 " + UUID.randomUUID())
                .queryText("idf")
                .requiredKeywords(List.of())
                .optionalKeywords(List.of(KEYWORD))
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("IDF 통합테스트 소스")
                .urlTemplate("https://example.com/idf-" + UUID.randomUUID())
                .language(LANGUAGE)
                .active(true)
                .build());
        CollectionRun run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("idf-" + UUID.randomUUID())
                .forceRefresh(false)
                .startedAt(NOW.minusDays(1))
                .build());
        String urlHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(urlHash)
                .canonicalUrl("https://example.com/" + urlHash)
                .title("Ordinary headline")
                .summary("A RareSummaryToken appears only in the CLOB summary")
                .language(LANGUAGE)
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(NOW.minusDays(1))
                .build());

        long documents = keywordIdfRepository.countDocuments(LANGUAGE, NOW.minusDays(30));
        long frequency = keywordIdfRepository.countDocumentsContaining(
                LANGUAGE, KEYWORD, NOW.minusDays(30));
        keywordIdfRepository.upsertAll(List.of(
                new CachedKeywordIdf(LANGUAGE, KEYWORD, documents, frequency, 1.0d, NOW)));

        CachedKeywordIdf cached = keywordIdfRepository.findAll(LANGUAGE, List.of(KEYWORD)).getFirst();
        assertEquals(1L, documents);
        assertEquals(1L, frequency);
        assertEquals(1L, cached.documentCount());
        assertEquals(1L, cached.documentFrequency());
        assertEquals(1.0d, cached.idf());
        assertEquals(NOW, cached.refreshedAt());
    }
}
