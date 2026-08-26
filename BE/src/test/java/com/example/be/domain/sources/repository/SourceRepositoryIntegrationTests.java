package com.example.be.domain.sources.repository;

import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SourceRepositoryIntegrationTests {

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReadsSourceWithCrawlPolicyJson() {
        Source saved = sourceRepository.save(source(Source.KIND_FEED, "ETNews 통합테스트",
                "https://example.com/rss-crawl-policy"));
        flushAndClear();

        assertNotNull(saved.getId());

        Source found = sourceRepository.findById(saved.getId()).orElseThrow();
        assertEquals("ETNews 통합테스트", found.getName());
        assertEquals(CrawlPolicy.ROBOTS_MODE_RESPECT, found.getCrawlPolicy().robotsMode());
        assertEquals(30, found.getCrawlPolicy().maxArticlesPerRun());
        assertTrue(found.getCrawlPolicy().fullTextAllowed());
        assertEquals(0, new BigDecimal("0.85").compareTo(found.getReliabilityScore()));
        assertTrue(found.isActive());
    }

    @Test
    void savesSourceWithoutCrawlPolicy() {
        Source source = Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("정책 없는 소스 통합테스트")
                .urlTemplate("https://example.com/rss-without-crawl-policy")
                .language("ko")
                .active(true)
                .build();
        Source saved = sourceRepository.save(source);
        flushAndClear();

        assertNull(sourceRepository.findById(saved.getId()).orElseThrow().getCrawlPolicy());
    }

    @Test
    void findsSourcesByFilterAndPage() {
        Source feed = sourceRepository.save(source(Source.KIND_FEED, "필터 FEED 통합테스트",
                "https://example.com/filter-feed"));
        Source search = source(Source.KIND_SEARCH, "필터 SEARCH 통합테스트", "https://example.com?q={query}&t=filter");
        search.changeActive(false);
        Source inactiveSearch = sourceRepository.save(search);
        flushAndClear();

        Page<Source> feedOnly = sourceRepository.findAll(
                SourceSpecification.filter(Source.KIND_FEED, true, "필터"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );
        assertEquals(1, feedOnly.getTotalElements());
        assertEquals(feed.getId(), feedOnly.getContent().get(0).getId());

        Page<Source> inactiveOnly = sourceRepository.findAll(
                SourceSpecification.filter(null, false, "필터 SEARCH"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );
        assertEquals(1, inactiveOnly.getTotalElements());
        assertEquals(inactiveSearch.getId(), inactiveOnly.getContent().get(0).getId());
        assertFalse(inactiveOnly.getContent().get(0).isActive());
    }

    @Test
    void checksDuplicateByKindAndUrlTemplate() {
        Source saved = sourceRepository.save(source(Source.KIND_FEED, "중복 통합테스트",
                "https://example.com/duplicate-rss"));
        flushAndClear();

        assertTrue(sourceRepository.existsBySourceKindAndUrlTemplate(
                Source.KIND_FEED, "https://example.com/duplicate-rss"));
        assertFalse(sourceRepository.existsBySourceKindAndUrlTemplate(
                Source.KIND_SEARCH, "https://example.com/duplicate-rss"));
        assertFalse(sourceRepository.existsBySourceKindAndUrlTemplateAndIdNot(
                Source.KIND_FEED, "https://example.com/duplicate-rss", saved.getId()));
    }

    @Test
    void readsLinkedTopicsFromInverseSide() {
        Source feed = sourceRepository.save(source(Source.KIND_FEED, "역방향 통합테스트",
                "https://example.com/inverse-rss"));
        Topic topic = Topic.builder()
                .name("역방향 주제 통합테스트")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
        topic.replaceSources(List.of(feed));
        Topic savedTopic = topicRepository.save(topic);
        flushAndClear();

        Source found = sourceRepository.findById(feed.getId()).orElseThrow();
        assertEquals(1, found.getLinkedTopicCount());
        assertEquals(savedTopic.getId(), found.getTopics().get(0).getId());

        List<SourceRepository.LinkedTopicCount> counts =
                sourceRepository.countLinkedTopics(List.of(feed.getId()));
        assertEquals(1, counts.size());
        assertEquals(feed.getId(), counts.get(0).getSourceId());
        assertEquals(1, counts.get(0).getLinkedTopicCount());
    }

    @Test
    void updatesSourceAndKeepsRecordOnDeactivation() {
        Source saved = sourceRepository.save(source(Source.KIND_FEED, "수정 통합테스트",
                "https://example.com/update-rss"));
        flushAndClear();

        Source loaded = sourceRepository.findById(saved.getId()).orElseThrow();
        loaded.update("수정 통합테스트 (개편)", "https://example.com/update-rss-v2", "US", "en",
                new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 20, false), new BigDecimal("0.70"), false);
        flushAndClear();

        Source reloaded = sourceRepository.findById(saved.getId()).orElseThrow();
        assertEquals("수정 통합테스트 (개편)", reloaded.getName());
        assertEquals("https://example.com/update-rss-v2", reloaded.getUrlTemplate());
        assertEquals("US", reloaded.getCountry());
        assertFalse(reloaded.getCrawlPolicy().fullTextAllowed());
        assertFalse(reloaded.isActive());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Source source(String sourceKind, String name, String urlTemplate) {
        return Source.builder()
                .sourceKind(sourceKind)
                .name(name)
                .urlTemplate(urlTemplate)
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .reliabilityScore(new BigDecimal("0.85"))
                .active(true)
                .build();
    }
}
