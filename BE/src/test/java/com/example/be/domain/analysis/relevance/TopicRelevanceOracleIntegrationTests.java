package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceRequest;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicRelevanceOracleIntegrationTests {
    @Autowired TopicRelevanceStore store;
    @Autowired TopicRelevanceFinalizer finalizer;
    @Autowired AgentQuotaService quota;
    @Autowired TopicRepository topics;
    @Autowired SourceRepository sources;
    @Autowired CollectionRunRepository runs;
    @Autowired ArticleRepository articles;
    @Autowired ArticleBodyStorage bodies;
    @Autowired JdbcTemplate jdbc;

    @Test void migrationRoundTripsScopedDecisionsAndAtomicallySettlesAuditAndQuota() {
        LocalDateTime started = LocalDateTime.now(ApiTimeZone.ZONE).minusMinutes(1);
        var run = runs.saveAndFlush(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(started).finishedAt(started.plusSeconds(30)).build());
        var topic = topic("제조장비");
        var other = topic("금융 규제");
        var source = sources.saveAndFlush(Source.builder().name("relevance-test " + UUID.randomUUID())
                .sourceKind(Source.KIND_FEED).urlTemplate("https://example.com/" + UUID.randomUUID())
                .country("KR").language("ko").active(true).robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true)).build());
        var article = articles.saveAndFlush(Article.builder().title("토스 공정위 분쟁").topic(topic).source(source)
                .urlHash(TopicRelevanceGate.hash(UUID.randomUUID().toString()))
                .canonicalUrl("https://example.com/" + UUID.randomUUID()).contentHash("b".repeat(64))
                .storedBody(bodies.intern("토스와 네이버의 금융 플랫폼 분쟁을 공정위가 조사한다."))
                .fetchStatus(FetchStatus.FULLTEXT).firstSeenRun(run).lastSeenRun(run).collectedAt(started).build());
        store.saveAll(List.of(assessment(run.getId(), topic.getId(), article.getId(), TopicRelevanceStatus.IRRELEVANT)));
        store.saveAll(List.of(assessment(run.getId(), other.getId(), article.getId(), TopicRelevanceStatus.UNCERTAIN)));
        String key = "topic-relevance-integration:" + UUID.randomUUID();
        var request = new AgentTopicRelevanceRequest(key, AgentPlan.FREE,
                new AgentTopicRelevanceRequest.TopicInput(other.getId(), other.getName(), other.getQueryText(), List.of(), List.of(), List.of()),
                List.of(new AgentTopicRelevanceRequest.ArticleInput(article.getId(), article.getTitle(), null, article.getBody())));
        var response = new AgentTopicRelevanceResponse(List.of(new AgentTopicRelevanceResponse.Decision(
                article.getId(), "RELEVANT", "금융 플랫폼 규제와 관련됩니다.", List.of("공정위가 조사한다"))),
                new AgentTopicRelevanceResponse.Meta("openai", "fixture-model", TopicRelevanceGate.PROMPT_VERSION,
                        10L, 5L, new BigDecimal("0.0000004"), BigDecimal.ZERO, false, false));
        assertDoesNotThrow(() -> TopicRelevanceGate.validate(response, request));
        var reservation = quota.reserve(run.getId(), key, AgentTask.TOPIC_RELEVANCE, AgentPlan.FREE);
        finalizer.success(run.getId(), request, response,
                List.of(assessment(run.getId(), other.getId(), article.getId(), TopicRelevanceStatus.RELEVANT)),
                "a".repeat(64), reservation, started);
        var persisted = store.findByRun(run.getId());
        assertEquals(2, persisted.size());
        assertEquals(TopicRelevanceStatus.IRRELEVANT, persisted.stream().filter(a -> a.topicId().equals(topic.getId())).findFirst().orElseThrow().status());
        assertEquals(TopicRelevanceStatus.RELEVANT, persisted.stream().filter(a -> a.topicId().equals(other.getId())).findFirst().orElseThrow().status());
        assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM agent_quota_reservations WHERE idempotency_key = ?", String.class, key));
        assertEquals("TOPIC_RELEVANCE", jdbc.queryForObject("SELECT agent_task FROM agent_runs WHERE idempotency_key = ?", String.class, key));
        assertEquals(2, store.latestOnDate(started.toLocalDate(), List.of(article.getId())).size());
    }

    private Topic topic(String name) {
        return topics.saveAndFlush(Topic.builder().name(name + UUID.randomUUID()).queryText(name)
                .requiredKeywords(List.of()).optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(10).intervalMinutes(60).active(true).build());
    }
    private TopicRelevanceStore.Assessment assessment(Long runId, Long topicId, Long articleId, TopicRelevanceStatus status) {
        return new TopicRelevanceStore.Assessment(runId, topicId, articleId, status, "주제 의미 판정 근거", "a".repeat(64),
                TopicRelevanceGate.PROMPT_VERSION, "fixture-model", "[\"공정위\"]");
    }
}
