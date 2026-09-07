package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionResultWriterIntegrationTests {

    @Autowired
    private CollectionResultWriter resultWriter;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long topicId;
    private Long sourceId;
    private Long fixtureRunId;

    /**
     * afterCommit 안에서 스레드풀 거절을 처리하는 경로다. failRun이 REQUIRED면 이미 커밋된 생성 트랜잭션의
     * 리소스에 붙어 변경이 커밋되지 않을 수 있으므로 새 트랜잭션이어야 한다.
     */
    @Test
    void failRunCommitsWhenCalledFromAfterCommitCallback() {
        Long runId = transactionTemplate.execute(status -> {
            Topic topic = topicRepository.save(topic());
            topicId = topic.getId();
            Source source = sourceRepository.save(source());
            sourceId = source.getId();
            CollectionRun run = CollectionRun.builder()
                    .status(RunStatus.RUNNING)
                    .triggerType(TriggerType.MANUAL)
                    .idempotencyKey("after-commit-" + UUID.randomUUID())
                    .forceRefresh(false)
                    .startedAt(LocalDateTime.now())
                    .build();
            run.addItem(CollectionRunItem.builder()
                    .topic(topic)
                    .source(source)
                    .status(RunItemStatus.RUNNING)
                    .build());
            CollectionRun saved = runRepository.save(run);
            // afterCommit 자체가 실패해도 이미 커밋된 실행을 정리할 수 있어야 한다.
            fixtureRunId = saved.getId();

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    resultWriter.failRun(saved.getId());
                }
            });
            return saved.getId();
        });

        CollectionRun reloaded = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED, reloaded.getStatus());
        assertNotNull(reloaded.getFinishedAt());
        assertEquals(1, runRepository.countWarnings(List.of(runId)).get(0).getWarningCount());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            if (fixtureRunId != null) {
                jdbcTemplate.update("DELETE FROM news_collection_run_warnings WHERE run_id = ?", fixtureRunId);
                jdbcTemplate.update("DELETE FROM news_collection_run_items WHERE run_id = ?", fixtureRunId);
                jdbcTemplate.update("DELETE FROM news_collection_runs WHERE id = ?", fixtureRunId);
            }
            if (topicId != null) {
                jdbcTemplate.update("DELETE FROM news_topic_sources WHERE topic_id = ?", topicId);
                jdbcTemplate.update("DELETE FROM news_topics WHERE id = ?", topicId);
            }
            if (sourceId != null) {
                jdbcTemplate.update("DELETE FROM news_sources WHERE id = ?", sourceId);
            }
        });
    }

    private Topic topic() {
        return Topic.builder()
                .name("afterCommit 테스트 주제 " + UUID.randomUUID())
                .queryText("HBM")
                .batchSize(10)
                .intervalMinutes(60)
                // 직접 만든 실행의 실패 정산만 검증하므로 자동 수집 대상일 필요가 없다.
                .active(false)
                .build();
    }

    private Source source() {
        return Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("afterCommit 테스트 소스")
                .urlTemplate("https://example.com/after-commit-" + UUID.randomUUID())
                .active(false)
                .build();
    }
}
