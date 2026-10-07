package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStage;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionRunStageWriterIntegrationTests {

    @Autowired
    private CollectionRunStageWriter stageWriter;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long fixtureRunId;

    @Test
    void commitsStageBeforeWorkEvenWhenCallingTransactionRollsBack() {
        Long runId = saveRun();

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            assertTrue(stageWriter.updateStage(runId, RunStage.ANALYZING));
            throw new IllegalStateException("subsequent work failed");
        }));

        CollectionRun found = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.RUNNING, found.getStatus());
        assertEquals(RunStage.ANALYZING, found.getStage());
    }

    @Test
    void doesNotResurrectFinishedRunsOrOverwriteTheirResults() {
        Long runId = saveRun();
        assertTrue(stageWriter.updateStage(runId, RunStage.GENERATING_REPORT));
        LocalDateTime finishedAt = LocalDateTime.of(2026, 10, 7, 9, 0);
        transactionTemplate.executeWithoutResult(status -> {
            CollectionRun run = runRepository.findById(runId).orElseThrow();
            run.recordAnalysisTargetIssueCount(17);
            run.finish(finishedAt);
        });

        assertFalse(stageWriter.updateStage(runId, RunStage.FINALIZING));

        CollectionRun found = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.SUCCESS, found.getStatus());
        assertEquals(finishedAt, found.getFinishedAt());
        assertEquals(17, found.getAnalysisTargetIssueCount());
        assertNull(found.getStage());
    }

    @Test
    void stageUpdatesLeaveQueuedRunsUnchanged() {
        Long runId = saveRun();
        transactionTemplate.executeWithoutResult(status ->
                runRepository.findById(runId).orElseThrow().returnToQueue());

        assertFalse(stageWriter.updateStage(runId, RunStage.COLLECTING));

        CollectionRun found = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.PENDING, found.getStatus());
        assertNull(found.getStage());
    }

    private Long saveRun() {
        fixtureRunId = transactionTemplate.execute(status -> {
            CollectionRun run = CollectionRun.builder()
                    .status(RunStatus.PENDING)
                    .triggerType(TriggerType.MANUAL)
                    .build();
            run.start();
            return runRepository.saveAndFlush(run).getId();
        });
        return fixtureRunId;
    }

    @AfterEach
    void cleanup() {
        if (fixtureRunId != null) {
            jdbcTemplate.update("DELETE FROM news_collection_runs WHERE id = ?", fixtureRunId);
        }
    }
}
