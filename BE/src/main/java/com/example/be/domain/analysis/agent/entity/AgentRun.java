package com.example.be.domain.analysis.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_runs")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collection_run_id")
    private Long collectionRunId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_task", nullable = false, length = 50)
    private AgentTask agentTask;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AgentTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AgentRunStatus status;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeout_phase", length = 10)
    private AgentTimeoutPhase timeoutPhase;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "llm_provider", length = 30)
    private String llmProvider;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_plan", length = 10)
    private AgentPlan llmPlan;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "credits", precision = 10, scale = 3)
    private BigDecimal credits;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "investigation_step")
    private Integer investigationStep;

    @Column(name = "investigation_action", length = 30)
    private String investigationAction;

    @Column(name = "action_reason", length = 1000)
    private String actionReason;

    @Column(name = "source_key", length = 100)
    private String sourceKey;

    @Column(name = "query_hash", length = 64)
    private String queryHash;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "action_payload")
    private String actionPayload;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "added_article_count", nullable = false)
    private int addedArticleCount;

    @Column(name = "evidence_before")
    private Integer evidenceBefore;

    @Column(name = "evidence_after")
    private Integer evidenceAfter;

    @Column(name = "termination_reason", length = 30)
    private String terminationReason;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
