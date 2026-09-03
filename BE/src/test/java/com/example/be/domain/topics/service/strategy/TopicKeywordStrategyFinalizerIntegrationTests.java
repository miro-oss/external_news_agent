package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordChangeAction;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicKeywordStrategyFinalizerIntegrationTests {

    @Autowired
    private TopicKeywordStrategyFinalizer finalizer;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rollsBackProposalAndAuditWhenQuotaSettlementFails() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString();
        Long topicId = transaction.execute(status -> topicRepository.saveAndFlush(Topic.builder()
                .name("rollback-topic-" + suffix)
                .queryText("HBM")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .batchSize(1)
                .intervalMinutes(60)
                .active(true)
                .build()).getId());
        Long runId = transaction.execute(status -> runRepository.saveAndFlush(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.SCHEDULED)
                .idempotencyKey("rollback-run-" + suffix)
                .startedAt(LocalDateTime.now())
                .llmPlan(AgentPlan.FREE)
                .build()).getId());
        String strategyKey = "rollback-strategy-" + suffix;

        try {
            AgentKeywordStrategyRequest request = request(runId, topicId, strategyKey);
            AgentKeywordStrategyResponse response = response();
            QuotaReservation missingReservation = new QuotaReservation(
                    Long.MAX_VALUE,
                    runId,
                    strategyKey,
                    AgentTask.KEYWORD_STRATEGY,
                    AgentPlan.FREE,
                    BigDecimal.ONE);

            assertThatThrownBy(() -> finalizer.completeSuccess(
                    runId,
                    topicId,
                    request,
                    response,
                    List.of(new TopicKeywordChange(
                            TopicKeywordBucket.OPTIONAL,
                            TopicKeywordChangeAction.ADD,
                            "HBM4",
                            "신규 기사에서 반복 등장했습니다.")),
                    LocalDateTime.now(),
                    missingReservation))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("quota 예약");

            assertThat(countProposals(strategyKey)).isZero();
            assertThat(countAgentRuns(strategyKey)).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM news_collection_runs WHERE id = ?", runId);
            jdbcTemplate.update("DELETE FROM news_topics WHERE id = ?", topicId);
        }
    }

    private AgentKeywordStrategyRequest request(Long runId, Long topicId, String strategyKey) {
        return new AgentKeywordStrategyRequest(
                strategyKey,
                AgentPlan.FREE,
                new AgentKeywordStrategyRequest.Target("TOPIC", topicId),
                new AgentKeywordStrategyRequest.Topic(
                        "HBM", "HBM", List.of("HBM"), List.of("SK하이닉스"), List.of("광고")),
                new AgentKeywordStrategyRequest.Run(runId, "SCHEDULED", 1, 1, 0),
                List.of(),
                List.of());
    }

    private AgentKeywordStrategyResponse response() {
        return new AgentKeywordStrategyResponse(
                "HBM4를 선택 키워드로 추가합니다.",
                List.of(new AgentKeywordStrategyResponse.Proposal(
                        "OPTIONAL", "ADD", "HBM4", "신규 기사에서 반복 등장했습니다.")),
                new AgentKeywordStrategyResponse.Meta(
                        "mock", "mock", "keyword-strategy.ko.v1",
                        0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }

    private int countProposals(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM news_topic_keyword_proposals WHERE idempotency_key = ?",
                Integer.class,
                idempotencyKey);
        return count == null ? 0 : count;
    }

    private int countAgentRuns(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?",
                Integer.class,
                idempotencyKey);
        return count == null ? 0 : count;
    }
}
