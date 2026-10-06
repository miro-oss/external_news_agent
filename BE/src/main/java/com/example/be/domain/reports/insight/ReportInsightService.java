package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.exception.code.LlmErrorCode;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor @Slf4j
public class ReportInsightService {
    public static final String PROMPT_VERSION = "report-insight.ko.v19";
    public static final String RUBRIC_VERSION = "report-importance.v6";
    private final AgentProperties properties;
    private final ReportInsightSnapshotAssembler assembler;
    private final ReportInsightPersistenceService persistence;
    private final ReportInsightValidator validator;
    private final AgentClient client;
    private final AgentQuotaService quota;
    private final LlmPlanService plans;
    private final AgentRunRecorder recorder;
    private final ReportInsightExecutionRecorder executions;
    private final ReportInsightJobRepository jobs;
    @Value("${news.scheduling.enabled:true}") private boolean schedulingEnabled = true;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ReportInsightDTO.Result create(Long reportId, ReportInsightDTO.CreateRequest body) {
        return create(reportId, body, false);
    }

    /** Only the durable worker may bypass its own queued/running state. */
    public ReportInsightDTO.Result createAutomatic(long reportId, Audience audience) {
        return create(reportId, new ReportInsightDTO.CreateRequest(List.of(audience.name())), true);
    }

    private ReportInsightDTO.Result create(Long reportId, ReportInsightDTO.CreateRequest body, boolean automatic) {
        positive(reportId);
        var audiences = audiences(body == null ? null : body.audiences());
        if (!properties.isEnabled()) throw new GeneralException(GeneralErrorCode.CONFLICT,
                "리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다.");
        var snapshot = assembler.assemble(reportId);
        Map<Audience, NewsReportInsight> cached = cached(snapshot, audiences);
        if (cached.size() == audiences.size()) {
            safeAudit(() -> recorder.recordReportInsightCacheHit(snapshot.runId(), reportId,
                    context(snapshot, audiences.size(), audiences.size(), false), now()));
            return result(true, snapshot, audiences, cached);
        }
        if (!automatic && schedulingEnabled) {
            for (var audience : audiences) {
                if (!cached.containsKey(audience) && jobs.isPending(reportId, audience)) throw inflight();
            }
        }
        boolean generated = false;
        for (Audience audience : audiences) {
            if (cached.containsKey(audience)) continue;
            // Independent perspectives may run concurrently; only duplicate executions share a guard.
            String key = reportId + ":" + snapshot.inputHash() + ":" + audience.name();
            if (!active.add(key)) throw inflight();
            try {
                cached.putAll(cached(snapshot, List.of(audience)));
                if (cached.containsKey(audience)) continue;
                var saved = generate(snapshot, audience);
                cached.put(saved.getAudience(), saved);
                generated = true;
            } finally { active.remove(key); }
        }
        return result(!generated, snapshot, audiences, cached);
    }

    public ReportInsightDTO.Result get(Long reportId, String audienceValue) {
        positive(reportId);
        Audience audience = audience(audienceValue);
        var snapshot = assembler.assembleForRead(reportId);
        if (snapshot.findings().isEmpty()) throw missing(reportId, audience);
        var cached = cached(snapshot, List.of(audience));
        if (cached.isEmpty()) throw missing(reportId, audience);
        return result(true, snapshot, List.of(audience), cached);
    }

    private NewsReportInsight generate(ReportInsightSnapshotAssembler.Snapshot snapshot, Audience audience) {
        var plan = plans.get().plan();
        QuotaReservation reservation;
        String key = "report-insight:" + snapshot.reportId() + ":" + snapshot.inputHash() + ":"
                + PROMPT_VERSION + ":" + RUBRIC_VERSION + ":" + audience.name();
        try { reservation = quota.reserveReportInsight(snapshot.runId(), key, plan); }
        catch (QuotaExceededException exception) { throw new LlmException(LlmErrorCode.QUOTA_EXHAUSTED,
                Map.of("plan", exception.getPlan().name(), "reason", exception.getMessage())); }
        catch (IllegalStateException exception) {
            var concurrent = persistence.findCached(snapshot.reportId(), snapshot.inputHash(), List.of(audience));
            if (!concurrent.isEmpty()) return concurrent.getFirst();
            throw inflight();
        }
        var request = new AgentReportInsightRequest(reservation.idempotencyKey(), plan, List.of(audience.name()),
                snapshot.report(), snapshot.findings());
        var audit = context(snapshot, 1, 0, true);
        LocalDateTime startedAt = now();
        AgentReportInsightResponse response = null;
        boolean validated = false;
        GenerationPhase phase = GenerationPhase.AGENT_CALL;
        NewsReportInsight saved;
        try {
            response = client.reportInsight(request);
            phase = GenerationPhase.RESPONSE_VALIDATION;
            validator.validate(response, request);
            validated = true;
            phase = GenerationPhase.USAGE_CHECK;
            var actualUnits = plan == AgentPlan.FREE ? java.math.BigDecimal.ONE : response.meta().credits();
            if (actualUnits.compareTo(reservation.reservedUnits()) > 0)
                throw new AgentClientException("BUDGET_EXCEEDED", "실제 LLM 사용량이 요청당 예약 상한을 초과했습니다.");
            phase = GenerationPhase.PERSISTENCE;
            saved = executions.success(snapshot, request, response, audit, startedAt, reservation);
        } catch (RuntimeException exception) {
            var failure = observedFailure(exception, response, validated);
            boolean auditPersisted = false;
            try {
                executions.failure(snapshot.runId(), request, failure, audit, startedAt, reservation);
                auditPersisted = true;
            }
            catch (RuntimeException accountingFailure) {
                log.error("리포트 인사이트 비용 기록 실패. 예약을 유지합니다. reportId={}", snapshot.reportId());
            }
            // Agent error messages, codes and causes can contain response data. Log only bounded metadata.
            var validation = failure.getValidationFailure();
            log.warn("리포트 관점 인사이트 생성 실패. reportId={}, audience={}, phase={}, failureCode={}, timeoutPhase={}, auditPersisted={}, validationStage={}, validationAttempt={}, validationErrorType={}, validationErrorCount={}, validationErrorKinds={}",
                    snapshot.reportId(), audience, phase, diagnosticFailureCode(failure), failure.getTimeoutPhase(), auditPersisted,
                    validation == null ? null : validation.stage(), validation == null ? null : validation.attempt(),
                    validation == null ? null : validation.errorType(), validation == null ? null : validation.errorCount(),
                    validation == null ? null : validation.errorKinds());
            throw new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR, "리포트 관점 인사이트 생성에 실패했습니다.");
        }
        return saved;
    }

    private enum GenerationPhase { AGENT_CALL, RESPONSE_VALIDATION, USAGE_CHECK, PERSISTENCE }

    private String diagnosticFailureCode(AgentClientException failure) {
        if (failure.getCode() == null) return "UNKNOWN";
        return switch (failure.getCode()) {
            case "SCHEMA_VIOLATION", "PROVIDER_UNAVAILABLE", "BUDGET_EXCEEDED", "API_KEY_MISSING",
                    "UNAUTHORIZED", "PERSISTENCE_FAILED" -> failure.getCode();
            default -> "UNKNOWN";
        };
    }

    private AgentClientException observedFailure(RuntimeException exception, AgentReportInsightResponse response, boolean validated) {
        if (response == null || response.meta() == null) {
            if (exception instanceof AgentClientException failure) return failure;
            return new AgentClientException("SCHEMA_VIOLATION", exception.getMessage(), exception);
        }
        var meta = response.meta();
        String code = exception instanceof AgentClientException failure ? failure.getCode()
                : validated ? "PERSISTENCE_FAILED" : "SCHEMA_VIOLATION";
        return new AgentClientException(code,
                exception.getMessage(), exception, new AgentClientException.Usage(nonNegative(meta.inputTokens()), nonNegative(meta.outputTokens()),
                nonNegative(meta.costUsd()), nonNegative(meta.credits())), AgentClientException.TimeoutPhase.NONE,
                new AgentClientException.ExecutionMetadata(meta.provider(), meta.model(), meta.promptVersion(), "RESPONSE"));
    }

    private Long nonNegative(Long value) { return value == null || value < 0 ? null : value; }
    private java.math.BigDecimal nonNegative(java.math.BigDecimal value) { return value == null || value.signum() < 0 ? null : value; }

    private Map<Audience, NewsReportInsight> cached(ReportInsightSnapshotAssembler.Snapshot snapshot, List<Audience> audiences) {
        Map<Audience, NewsReportInsight> result = new EnumMap<>(Audience.class);
        persistence.findCached(snapshot.reportId(), snapshot.inputHash(), audiences).forEach(row -> result.put(row.getAudience(), row));
        return result;
    }
    private ReportInsightDTO.Result result(boolean cached, ReportInsightSnapshotAssembler.Snapshot snapshot,
            List<Audience> audiences, Map<Audience, NewsReportInsight> rows) {
        return new ReportInsightDTO.Result(cached, snapshot.reportId(), snapshot.inputHash(), PROMPT_VERSION, RUBRIC_VERSION,
                snapshot.findings().size(), audiences.stream().map(rows::get).map(persistence::toDto).toList());
    }
    private List<Audience> audiences(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > Audience.values().length) throw new AudienceException();
        var parsed = values.stream().map(this::audience).toList();
        if (new HashSet<>(parsed).size() != parsed.size()) throw new AudienceException();
        return parsed;
    }
    private Audience audience(String value) {
        try { return Audience.fromApiValue(value); }
        catch (IllegalArgumentException exception) { throw new AudienceException(); }
    }
    private void positive(Long reportId) {
        if (reportId == null || reportId <= 0) throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "reportId는 양수여야 합니다.");
    }
    private ReportInsightAuditContext context(ReportInsightSnapshotAssembler.Snapshot snapshot, int requested, int cached, boolean issued) {
        return new ReportInsightAuditContext(1, "REPORT_INSIGHT_OBSERVATION", snapshot.inputHash(), PROMPT_VERSION,
                RUBRIC_VERSION, snapshot.findings().size(), requested, cached, issued);
    }
    private GeneralException inflight() { return new GeneralException(GeneralErrorCode.CONFLICT,
            "동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요."); }
    private GeneralException missing(long reportId, Audience audience) {
        if (properties.isEnabled() && schedulingEnabled && jobs.isPending(reportId, audience)) return inflight();
        return new GeneralException(GeneralErrorCode.NOT_FOUND, "저장된 리포트 관점 인사이트가 없습니다.");
    }
    private LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
    private void safeAudit(Runnable action) {
        try { action.run(); } catch (RuntimeException exception) { log.warn("리포트 인사이트 감사 기록 실패: {}", exception.getClass().getSimpleName()); }
    }
}
