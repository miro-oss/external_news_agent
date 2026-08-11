package com.example.be.domain.topics.repository;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicRepositoryIntegrationTests {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReadsTopicWithKeywordJson() {
        Topic saved = topicRepository.save(topic("HBM 통합테스트"));
        flushAndClear();

        assertNotNull(saved.getId());

        Topic found = topicRepository.findById(saved.getId()).orElseThrow();
        assertEquals("HBM 통합테스트", found.getName());
        assertEquals("HBM 반도체", found.getQueryText());
        assertEquals(List.of("HBM"), found.getRequiredKeywords());
        assertEquals(List.of("SK하이닉스", "삼성전자"), found.getOptionalKeywords());
        assertEquals(List.of("광고"), found.getExcludedKeywords());
        assertEquals(10, found.getBatchSize());
        assertEquals(60, found.getIntervalMinutes());
        assertTrue(found.isActive());
    }

    @Test
    void findsTopicsByFilterAndPage() {
        Topic active = topicRepository.save(topic("HBM 활성 통합테스트"));
        Topic inactiveTopic = topic("DRAM 비활성 통합테스트");
        inactiveTopic.changeActive(false);
        Topic inactive = topicRepository.save(inactiveTopic);
        flushAndClear();

        Page<Topic> page = topicRepository.findAll(
                TopicSpecification.filter(true, "HBM 활성"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );

        assertEquals(1, page.getTotalElements());
        assertEquals(active.getId(), page.getContent().get(0).getId());
        assertTrue(topicRepository.existsByName("HBM 활성 통합테스트"));
        assertFalse(topicRepository.existsByNameAndIdNot("HBM 활성 통합테스트", active.getId()));

        Page<Topic> inactiveOnly = topicRepository.findAll(
                TopicSpecification.filter(false, "DRAM 비활성"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );
        assertEquals(1, inactiveOnly.getTotalElements());
        assertEquals(inactive.getId(), inactiveOnly.getContent().get(0).getId());
        assertFalse(inactiveOnly.getContent().get(0).isActive());
    }

    @Test
    void findsTopicsByKeywordContainingLikeWildcard() {
        Topic saved = topicRepository.save(topic("수율 100% 통합테스트"));
        topicRepository.save(topic("수율 100퍼센트 통합테스트"));
        flushAndClear();

        Page<Topic> page = topicRepository.findAll(
                TopicSpecification.filter(null, "100%"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );

        assertEquals(1, page.getTotalElements());
        assertEquals(saved.getId(), page.getContent().get(0).getId());
    }

    @Test
    void linksSourcesAndCountsThem() {
        Source feed = sourceRepository.save(source(Source.KIND_FEED, "ETNews 통합테스트", "https://example.com/rss"));
        Source search = sourceRepository.save(
                source(Source.KIND_SEARCH, "Google News 통합테스트", "https://example.com?q={query}"));

        Topic topic = topic("소스 연결 통합테스트");
        topic.replaceSources(List.of(feed, search));
        Topic saved = topicRepository.save(topic);
        flushAndClear();

        Topic found = topicRepository.findById(saved.getId()).orElseThrow();
        assertEquals(2, found.getLinkedSourceCount());
        assertTrue(found.getSources().stream().anyMatch(Source::isSearchKind));

        List<TopicRepository.LinkedSourceCount> counts =
                topicRepository.countLinkedSources(List.of(saved.getId()));
        assertEquals(1, counts.size());
        assertEquals(saved.getId(), counts.get(0).getTopicId());
        assertEquals(2, counts.get(0).getLinkedSourceCount());
    }

    @Test
    void deletingTopicRemovesLinksButKeepsSources() {
        Source feed = sourceRepository.save(source(Source.KIND_FEED, "삭제 통합테스트", "https://example.com/delete-rss"));
        Topic topic = topic("연결 삭제 통합테스트");
        topic.replaceSources(List.of(feed));
        Topic saved = topicRepository.save(topic);
        flushAndClear();

        topicRepository.delete(topicRepository.findById(saved.getId()).orElseThrow());
        flushAndClear();

        assertTrue(topicRepository.findById(saved.getId()).isEmpty());
        assertTrue(sourceRepository.findById(feed.getId()).isPresent());
        assertEquals(List.of(), topicRepository.countLinkedSources(List.of(saved.getId())));
    }

    @Test
    void updatesAndDeletesTopic() {
        Topic saved = topicRepository.save(topic("수정 통합테스트"));
        flushAndClear();

        Topic loaded = topicRepository.findById(saved.getId()).orElseThrow();
        loaded.update("수정 통합테스트", "HBM4 반도체", List.of("HBM"), List.of("SK하이닉스"),
                List.of("광고", "채용", "주가"), 20, 30, false);
        flushAndClear();

        Topic reloaded = topicRepository.findById(saved.getId()).orElseThrow();
        assertEquals("HBM4 반도체", reloaded.getQueryText());
        assertEquals(List.of("광고", "채용", "주가"), reloaded.getExcludedKeywords());
        assertEquals(20, reloaded.getBatchSize());
        assertEquals(30, reloaded.getIntervalMinutes());
        assertFalse(reloaded.isActive());

        topicRepository.delete(reloaded);
        flushAndClear();

        assertTrue(topicRepository.findById(saved.getId()).isEmpty());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Topic topic(String name) {
        return Topic.builder()
                .name(name)
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스", "삼성전자"))
                .excludedKeywords(List.of("광고"))
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }

    private Source source(String sourceKind, String name, String urlTemplate) {
        return Source.builder()
                .sourceKind(sourceKind)
                .name(name)
                .urlTemplate(urlTemplate)
                .language("ko")
                .robotsStatus("allowed")
                .active(true)
                .build();
    }
}
