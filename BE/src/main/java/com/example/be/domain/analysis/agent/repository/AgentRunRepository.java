package com.example.be.domain.analysis.agent.repository;

import com.example.be.domain.analysis.agent.entity.AgentRun;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT DISTINCT run.targetId FROM AgentRun run
            WHERE run.collectionRunId = :runId
              AND run.agentTask = com.example.be.domain.analysis.agent.entity.AgentTask.ANALYZE
              AND run.targetType = com.example.be.domain.analysis.agent.entity.AgentTargetType.ARTICLE
              AND run.status = com.example.be.domain.analysis.agent.entity.AgentRunStatus.FAILED
            ORDER BY run.targetId
            """)
    List<Long> findFailedAnalysisArticleIds(@Param("runId") Long runId);
}
