package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentReportComparisonAnalyzer implements ReportComparisonAnalyzer {
    public static final String PROMPT_VERSION = "report-changes.ko.v1";
    private static final Set<String> TYPES = Set.of("UPDATED", "REFUTATION", "UNCHANGED", "UNDETERMINED");
    private static final Set<String> PROVIDERS = Set.of("mock", "openai", "mindlogic-claude");
    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentQuotaService quota;
    private final LlmPlanService planService;
    private final ReportComparisonRunRecorder recorder;
    private final ObjectMapper mapper;

    @Override
    public List<ComparisonAssessment> analyze(long reportId, long baseReportId, List<ComparisonCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        if (!properties.isEnabled()) throw new IllegalStateException("보고서 변화 비교 Agent가 비활성화되어 있습니다.");
        AgentPlan plan = planService.resolveRunPlan(null);
        String key = "report-changes:" + reportId + ":v1";
        validateRequest(new AgentReportChangesRequest(key, plan, reportId, baseReportId, candidates));
        QuotaReservation reservation = reserve(key, plan);
        var request = new AgentReportChangesRequest(reservation.idempotencyKey(), reservation.plan(), reportId, baseReportId, candidates);
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentReportChangesResponse response = null;
        try {
            response = client.reportChanges(request);
            validateResponse(response, candidates);
            AgentReportChangesResponse recorded = response;
            safely(() -> recorder.success(request, recorded, startedAt), reportId);
            safely(() -> quota.completeSuccess(reservation, recorded.meta().credits()), reportId);
            return List.copyOf(response.items());
        } catch (RuntimeException error) {
            AgentClientException failure = asFailure(error, response, reservation);
            safely(() -> recorder.failure(request, failure, startedAt), reportId);
            safely(() -> quota.completeFailure(reservation, failure), reportId);
            throw failure;
        }
    }

    private QuotaReservation reserve(String key, AgentPlan plan) {
        try {
            return quota.reserve(null, key, AgentTask.REPORT_CHANGES, plan);
        } catch (QuotaExceededException exhausted) {
            if (plan != AgentPlan.PAID || planService.paidExhaustedAction() != PaidExhaustedAction.FALLBACK_FREE) throw exhausted;
            return quota.reserve(null, key + ":fallback-free", AgentTask.REPORT_CHANGES, AgentPlan.FREE);
        }
    }

    private void validateRequest(AgentReportChangesRequest request) {
        if (request.reportId() < 1 || request.baseReportId() < 1 || request.reportId() == request.baseReportId()
                || request.candidates().size() > 50 || mapper.writeValueAsString(request).length() > 100_000) throw invalid();
        Set<String> ids = new HashSet<>();
        for (var candidate : request.candidates()) {
            if (candidate == null || !text(candidate.id(), 200) || !ids.add(candidate.id())
                    || candidate.relation() == null || !Set.of("SAME_ISSUE", "MERGED", "REFUTES").contains(candidate.relation())) throw invalid();
            validateClaims(candidate.previous());
            validateClaims(candidate.current());
        }
    }

    private void validateClaims(List<ComparisonClaimInput> claims) {
        if (claims == null || claims.isEmpty() || claims.size() > 6) throw invalid();
        Set<String> ids = new HashSet<>();
        for (var claim : claims) {
            if (claim == null || !text(claim.id(), 200) || !ids.add(claim.id()) || !text(claim.text(), 600)
                    || claim.evidence() == null || claim.evidence().isEmpty() || claim.evidence().size() > 3
                    || claim.evidence().stream().anyMatch(value -> !text(value, 600))) throw invalid();
        }
    }

    static void validateResponse(AgentReportChangesResponse response, List<ComparisonCandidate> candidates) {
        if (response == null || response.meta() == null || response.items() == null || response.items().size() != candidates.size()) throw invalid();
        var meta = response.meta();
        if (meta.provider() == null || !PROVIDERS.contains(meta.provider()) || !text(meta.model(), 100)
                || meta.model().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100 || !PROMPT_VERSION.equals(meta.promptVersion())
                || meta.inputTokens() == null || meta.inputTokens() < 0 || meta.outputTokens() == null || meta.outputTokens() < 0
                || !validAmount(meta.costUsd(), 6, 6) || !validAmount(meta.credits(), 7, 3) || meta.mock() == null
                || !Boolean.FALSE.equals(meta.truncated())) throw invalid();
        var byId = new HashMap<String, ComparisonCandidate>();
        candidates.forEach(candidate -> byId.put(candidate.id(), candidate));
        var seen = new HashSet<String>();
        for (var item : response.items()) {
            if (item == null || !seen.add(item.candidateId()) || item.type() == null || !TYPES.contains(item.type()) || !text(item.summary(), 1600)) throw invalid();
            var candidate = byId.get(item.candidateId());
            if (candidate == null || !validReferences(item.previousClaimIds(), candidate.previous())
                    || !validReferences(item.currentClaimIds(), candidate.current())) throw invalid();
            boolean changed = "UPDATED".equals(item.type()) || "REFUTATION".equals(item.type());
            if (changed && (item.previousClaimIds().isEmpty() || item.currentClaimIds().isEmpty())) throw invalid();
            if ("REFUTATION".equals(item.type()) && !"REFUTES".equals(candidate.relation())) throw invalid();
        }
    }

    private static boolean validReferences(List<String> ids, List<ComparisonClaimInput> claims) {
        if (ids == null || ids.size() > 6 || new HashSet<>(ids).size() != ids.size()) return false;
        Set<String> allowed = new HashSet<>();
        claims.forEach(claim -> allowed.add(claim.id()));
        return allowed.containsAll(ids);
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean nonNegative(BigDecimal value) { return value != null && value.signum() >= 0; }
    private static boolean validAmount(BigDecimal value, int integerDigits, int scale) {
        // Compare before rescaling, including the database rounding boundary and extreme exponents.
        BigDecimal upperExclusive = BigDecimal.TEN.pow(integerDigits)
                .subtract(BigDecimal.valueOf(5).scaleByPowerOfTen(-scale - 1));
        return nonNegative(value) && value.compareTo(upperExclusive) < 0;
    }
    private static AgentClientException invalid() { return new AgentClientException("SCHEMA_VIOLATION", "보고서 변화 비교 계약을 위반했습니다."); }

    private static AgentClientException asFailure(RuntimeException error, AgentReportChangesResponse response, QuotaReservation reservation) {
        AgentClientException original = error instanceof AgentClientException value ? value : invalid();
        var usage = original.getUsage();
        if (usage == null && response != null && response.meta() != null) {
            var meta = response.meta();
            usage = new AgentClientException.Usage(meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits());
        }
        if (usage == null && original.isReadTimeout()) usage = new AgentClientException.Usage(null, null, null, reservation.reservedUnits());
        if (usage != null) {
            // Invalid metadata must not prevent the failure audit or erase reserved exposure.
            usage = new AgentClientException.Usage(
                    usage.inputTokens() == null || usage.inputTokens() < 0 ? null : usage.inputTokens(),
                    usage.outputTokens() == null || usage.outputTokens() < 0 ? null : usage.outputTokens(),
                    validAmount(usage.costUsd(), 6, 6) ? usage.costUsd() : null,
                    validAmount(usage.credits(), 7, 3) ? usage.credits() : reservation.reservedUnits());
        }
        var execution = original.getExecutionMetadata();
        if (execution == null && response != null && response.meta() != null) {
            var meta = response.meta();
            execution = new AgentClientException.ExecutionMetadata(meta.provider(), meta.model(), meta.promptVersion(), "AGENT_RESPONSE");
        }
        return new AgentClientException(original.getCode(), original.getMessage(), error, usage, original.getTimeoutPhase(), execution);
    }

    private void safely(Runnable action, long reportId) {
        try { action.run(); }
        catch (RuntimeException error) { log.error("보고서 비교 사용량 기록/정산 실패. reportId={}", reportId, error); }
    }
}
