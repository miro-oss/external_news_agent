package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.feedback.FeedbackResultValidator;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;

/** Every feedback model call shares the existing quota and audit ledger. */
@Service
@RequiredArgsConstructor
public class FeedbackAgentGateway {
    private final AgentClient client;
    private final AgentQuotaService quota;
    private final AgentRunJdbcRepository runs;
    private final PlatformTransactionManager transactions;
    private final AgentProperties properties;

    public JsonNode review(Long runId, Long targetId, JsonNode request) {
        return call(runId, targetId, request, AgentTask.FEEDBACK_REVIEW);
    }

    public JsonNode evaluate(Long runId, Long targetId, JsonNode request) {
        return call(runId, targetId, request, AgentTask.FEEDBACK_EVALUATE);
    }

    private JsonNode call(Long runId, Long targetId, JsonNode request, AgentTask task) {
        if (!properties.isEnabled()) throw new AgentClientException("PROVIDER_UNAVAILABLE", "Agent가 비활성화되어 있습니다.");
        AgentPlan plan = AgentPlan.valueOf(request.path("plan").asText());
        String key = request.path("idempotencyKey").asText();
        var reservation = quota.reserve(runId, key, task, plan);
        LocalDateTime started = LocalDateTime.now(ApiTimeZone.ZONE);
        JsonNode response = null;
        try {
            response = task == AgentTask.FEEDBACK_REVIEW ? client.feedbackReview(request) : client.feedbackEvaluate(request);
            JsonNode meta = response == null ? null : response.get("meta");
            validate(meta);
            if (task == AgentTask.FEEDBACK_REVIEW) FeedbackResultValidator.review(request,response);
            else FeedbackResultValidator.evaluate(request,response);
            var audit = base(runId, targetId, request, task, plan, key, started)
                    .status(meta.path("mock").asBoolean() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                    .promptVersion(meta.path("promptVersion").asText()).llmProvider(meta.path("provider").asText())
                    .llmModel(meta.path("model").asText()).inputTokens(meta.path("inputTokens").asLong())
                    .outputTokens(meta.path("outputTokens").asLong()).costUsd(amount(meta,"costUsd",6))
                    .credits(amount(meta,"credits",3)).build();
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                runs.insertIfAbsent(audit);
                quota.completeSuccess(reservation, amount(meta,"credits",6));
            });
            return response;
        } catch (RuntimeException failure) {
            AgentClientException safe = failure instanceof AgentClientException known ? known
                    : new AgentClientException("SCHEMA_VIOLATION", "피드백 판단 응답을 검증하지 못했습니다.", failure,
                            usage(response));
            var usage = safe.getUsage();
            var audit = base(runId,targetId,request,task,plan,key,started).status(AgentRunStatus.FAILED)
                    .failureCode(safe.getCode()).failureMessage("피드백 판단을 완료하지 못했습니다.")
                    .timeoutPhase(safe.isReadTimeout() ? AgentTimeoutPhase.READ : safe.isConnectTimeout() ? AgentTimeoutPhase.CONNECT : null)
                    .inputTokens(usage == null ? null : usage.inputTokens()).outputTokens(usage == null ? null : usage.outputTokens())
                    .costUsd(usage == null ? null : rounded(usage.costUsd(),6)).credits(usage == null ? null : rounded(usage.credits(),3)).build();
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                runs.insertIfAbsent(audit);
                if (safe.getUsage() == null && !safe.isConnectTimeout()) {
                    // A response without usable accounting cannot prove the provider was never called.
                    quota.completeFailure(reservation,"FEEDBACK_USAGE_UNKNOWN");
                } else quota.completeFailure(reservation,safe);
            });
            throw safe;
        }
    }

    private AgentRun.AgentRunBuilder base(Long runId, Long targetId, JsonNode request, AgentTask task,
                                         AgentPlan plan, String key, LocalDateTime started) {
        return AgentRun.builder().collectionRunId(runId).targetId(targetId)
                .targetType(task == AgentTask.FEEDBACK_REVIEW ? AgentTargetType.FINDING : AgentTargetType.TOPIC)
                .agentTask(task).llmPlan(plan).idempotencyKey(key).requestHash(hash(request.toString()))
                .startedAt(started).finishedAt(LocalDateTime.now(ApiTimeZone.ZONE));
    }

    static void validate(JsonNode meta) {
        if (meta == null || !meta.isObject()
                || !Set.of("mock","openai","mindlogic-claude").contains(meta.path("provider").asText())
                || !meta.path("mock").isBoolean() || meta.path("mock").asBoolean() != "mock".equals(meta.path("provider").asText())
                || !text(meta,"model",100) || !text(meta,"promptVersion",50)
                || !meta.path("truncated").isBoolean() || meta.path("truncated").asBoolean()
                || !meta.path("inputTokens").isIntegralNumber() || meta.path("inputTokens").asLong() < 0
                || !meta.path("outputTokens").isIntegralNumber() || meta.path("outputTokens").asLong() < 0
                || !nonnegative(meta,"costUsd") || !nonnegative(meta,"credits")) {
            throw new IllegalArgumentException("Invalid feedback usage metadata");
        }
    }

    private static boolean text(JsonNode node,String name,int max) {
        return node.path(name).isTextual() && !node.path(name).asText().isBlank()
                && node.path(name).asText().getBytes(StandardCharsets.UTF_8).length <= max;
    }
    private static boolean nonnegative(JsonNode node,String name) {
        return node.path(name).isNumber() && node.path(name).decimalValue().signum() >= 0
                && node.path(name).decimalValue().compareTo(BigDecimal.valueOf(1_000_000)) < 0;
    }
    private static BigDecimal amount(JsonNode node,String name,int scale) { return rounded(node.path(name).decimalValue(),scale); }
    private static BigDecimal rounded(BigDecimal value,int scale) { return value == null ? null : value.setScale(scale,RoundingMode.DOWN); }
    private static AgentClientException.Usage usage(JsonNode response) {
        if (response == null) return null;
        var meta = response.path("meta");
        if (!nonnegative(meta,"costUsd") || !nonnegative(meta,"credits")) return null;
        return new AgentClientException.Usage(Math.max(0,meta.path("inputTokens").asLong()),
                Math.max(0,meta.path("outputTokens").asLong()), amount(meta,"costUsd",6),amount(meta,"credits",6));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
