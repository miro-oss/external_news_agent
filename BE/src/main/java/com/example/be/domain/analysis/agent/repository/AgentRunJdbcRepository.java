package com.example.be.domain.analysis.agent.repository;

import com.example.be.domain.analysis.agent.entity.AgentRun;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** DB 유니크 제약을 기준으로 감사 행을 원자적으로 한 번만 기록한다. */
@Repository
@RequiredArgsConstructor
public class AgentRunJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_runs (
                collection_run_id, idempotency_key, agent_task, target_type, target_id,
                status, failure_code, failure_message, timeout_phase, prompt_version, llm_provider,
                llm_model, llm_plan, input_tokens, output_tokens, cost_usd, credits,
                request_hash, investigation_step, investigation_action, action_reason,
                source_key, query_hash, action_payload, rejection_reason, added_article_count,
                evidence_before, evidence_after, termination_reason, started_at, finished_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
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
                    run.getTimeoutPhase() == null ? null : run.getTimeoutPhase().name(),
                    run.getPromptVersion(),
                    run.getLlmProvider(),
                    run.getLlmModel(),
                    run.getLlmPlan() == null ? null : run.getLlmPlan().name(),
                    run.getInputTokens(),
                    run.getOutputTokens(),
                    run.getCostUsd(),
                    run.getCredits(),
                    run.getRequestHash(),
                    run.getInvestigationStep(),
                    run.getInvestigationAction(),
                    run.getActionReason(),
                    run.getSourceKey(),
                    run.getQueryHash(),
                    run.getActionPayload(),
                    run.getRejectionReason(),
                    run.getAddedArticleCount(),
                    run.getEvidenceBefore(),
                    run.getEvidenceAfter(),
                    run.getTerminationReason(),
                    run.getStartedAt(),
                    run.getFinishedAt());
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public boolean existsInvestigationQueryHash(Long runId, String queryHash) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_runs
                WHERE collection_run_id = ?
                  AND agent_task = 'INVESTIGATE'
                  AND query_hash = ?
                """, Integer.class, runId, queryHash);
        return count != null && count > 0;
    }

    public List<InvestigationStep> findInvestigationSteps(Long runId, Long issueId) {
        return jdbcTemplate.query("""
                SELECT status, investigation_step, investigation_action, action_reason,
                       rejection_reason, evidence_after
                FROM agent_runs
                WHERE collection_run_id = ?
                  AND target_type = 'ISSUE' AND target_id = ?
                  AND agent_task = 'INVESTIGATE'
                  AND investigation_step IS NOT NULL
                ORDER BY investigation_step ASC
                """, (rs, rowNum) -> new InvestigationStep(
                rs.getInt("investigation_step"),
                rs.getString("investigation_action"),
                ("SUCCESS".equals(rs.getString("status"))
                        || "MOCK".equals(rs.getString("status")))
                        && rs.getString("rejection_reason") == null,
                rs.getString("rejection_reason") == null
                        ? rs.getString("action_reason")
                        : rs.getString("rejection_reason"),
                rs.getObject("evidence_after") == null
                        ? 0 : rs.getInt("evidence_after")), runId, issueId);
    }

    public record InvestigationStep(
            int step,
            String action,
            boolean accepted,
            String summary,
            int evidenceCount
    ) {
    }
}
