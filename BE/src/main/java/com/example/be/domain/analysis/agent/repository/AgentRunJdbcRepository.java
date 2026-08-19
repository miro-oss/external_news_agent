package com.example.be.domain.analysis.agent.repository;

import com.example.be.domain.analysis.agent.entity.AgentRun;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DB 유니크 제약을 기준으로 감사 행을 원자적으로 한 번만 기록한다. */
@Repository
@RequiredArgsConstructor
public class AgentRunJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_runs (
                collection_run_id, idempotency_key, agent_task, target_type, target_id,
                status, failure_code, failure_message, prompt_version, llm_provider,
                llm_model, llm_plan, input_tokens, output_tokens, cost_usd, credits,
                request_hash, started_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public boolean insertIfAbsent(AgentRun run) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    run.getCollectionRunId(),
                    run.getIdempotencyKey(),
                    run.getAgentTask().name(),
                    run.getTargetType().name(),
                    run.getTargetId(),
                    run.getStatus().name(),
                    run.getFailureCode(),
                    run.getFailureMessage(),
                    run.getPromptVersion(),
                    run.getLlmProvider(),
                    run.getLlmModel(),
                    run.getLlmPlan() == null ? null : run.getLlmPlan().name(),
                    run.getInputTokens(),
                    run.getOutputTokens(),
                    run.getCostUsd(),
                    run.getCredits(),
                    run.getRequestHash(),
                    run.getStartedAt(),
                    run.getFinishedAt());
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }
}
