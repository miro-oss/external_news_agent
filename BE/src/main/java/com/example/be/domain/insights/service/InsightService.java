package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.dto.InsightDTO;
import com.example.be.domain.insights.entity.NewsInsight;
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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InsightService {

    private static final Set<String> GROUNDEDNESS = Set.of("grounded", "weak", "ungrounded");

    private final AgentProperties properties;
    private final InsightInputAssembler inputAssembler;
    private final InsightPersistenceService persistenceService;
    private final AgentClient agentClient;
    private final AgentQuotaService quotaService;
    private final AgentRunRecorder runRecorder;
    private final LlmPlanService planService;

    public InsightDTO.Result create(InsightDTO.CreateRequest createRequest) {
        AgentTargetType targetType = targetType(createRequest == null
                ? null : createRequest.targetType());
        Long targetId = targetId(createRequest == null ? null : createRequest.targetId());
        List<Audience> audiences = audiences(createRequest == null
                ? null : createRequest.audiences());
        InsightInputAssembler.Snapshot snapshot = inputAssembler.assemble(targetId);

        Map<Audience, NewsInsight> byAudience = new HashMap<>();
        persistenceService.findCached(
                        targetType,
                        targetId,
                        snapshot.inputHash(),
                        properties.getInsightPromptVersion(),
                        audiences)
                .forEach(insight -> byAudience.put(insight.getAudience(), insight));
        List<Audience> missing = audiences.stream()
                .filter(audience -> !byAudience.containsKey(audience))
                .toList();
        if (missing.isEmpty()) {
            return result(true, targetType, targetId, snapshot.inputHash(),
                    properties.getInsightPromptVersion(), audiences, byAudience);
        }
        if (!properties.isEnabled()) {
            throw new GeneralException(
                    GeneralErrorCode.INTERNAL_SERVER_ERROR,
                    "Agent 인사이트 기능이 비활성화되어 있습니다.");
        }

        AgentPlan plan = planService.get().plan();
        String idempotencyKey = idempotencyKey(targetId, snapshot.inputHash());
        QuotaReservation reservation;
        try {
            reservation = quotaService.reserve(
                    snapshot.runId(), idempotencyKey, AgentTask.INSIGHT, plan);
        } catch (QuotaExceededException exception) {
            throw new LlmException(LlmErrorCode.QUOTA_EXHAUSTED, Map.of(
                    "plan", exception.getPlan().name(),
                    "reason", exception.getMessage()));
        }

        AgentInsightRequest request = new AgentInsightRequest(
                idempotencyKey,
                plan,
                missing.stream().map(Enum::name).toList(),
                new AgentInsightRequest.TargetPayload(targetType.name(), targetId),
                snapshot.topic(),
                snapshot.findings());
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentInsightResponse response;
        try {
            response = agentClient.insight(request);
            validate(response, request);
        } catch (RuntimeException exception) {
            recordFailure(snapshot.runId(), targetId, request, exception, startedAt);
            completeFailure(reservation, exception);
            throw new GeneralException(
                    GeneralErrorCode.INTERNAL_SERVER_ERROR,
                    "관점 인사이트 생성에 실패했습니다.");
        }

        recordSuccess(snapshot.runId(), targetId, request, response, startedAt);
        quotaService.completeSuccess(reservation, response.meta().credits());
        persistenceService.saveGenerated(targetType, targetId, snapshot.inputHash(), response)
                .forEach(insight -> byAudience.put(insight.getAudience(), insight));
        return result(false, targetType, targetId, snapshot.inputHash(),
                response.meta().promptVersion(), audiences, byAudience);
    }

    public InsightDTO.Result get(String targetTypeValue, Long targetIdValue, String audienceValue) {
        AgentTargetType targetType = targetType(targetTypeValue);
        Long targetId = targetId(targetIdValue);
        Audience audience = audience(audienceValue);
        NewsInsight insight = persistenceService.findLatest(targetType, targetId, audience)
                .orElseThrow(() -> new GeneralException(
                        GeneralErrorCode.NOT_FOUND,
                        "저장된 관점 인사이트가 없습니다."));
        return new InsightDTO.Result(
                true,
                targetType.name(),
                targetId,
                insight.getInputHash(),
                insight.getPromptVersion(),
                List.of(persistenceService.toDto(insight)));
    }

    private InsightDTO.Result result(boolean cached,
                                     AgentTargetType targetType,
                                     Long targetId,
                                     String inputHash,
                                     String promptVersion,
                                     List<Audience> order,
                                     Map<Audience, NewsInsight> byAudience) {
        return new InsightDTO.Result(
                cached,
                targetType.name(),
                targetId,
                inputHash,
                promptVersion,
                order.stream().map(byAudience::get).map(persistenceService::toDto).toList());
    }

    private AgentTargetType targetType(String value) {
        if (!AgentTargetType.ISSUE.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
            throw new GeneralException(
                    GeneralErrorCode.BAD_REQUEST,
                    "targetType은 ISSUE여야 합니다.");
        }
        return AgentTargetType.ISSUE;
    }

    private Long targetId(Long value) {
        if (value == null || value <= 0) {
            throw new GeneralException(
                    GeneralErrorCode.BAD_REQUEST,
                    "targetId는 양수여야 합니다.");
        }
        return value;
    }

    private List<Audience> audiences(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > Audience.values().length) {
            throw new AudienceException();
        }
        LinkedHashSet<Audience> parsed = new LinkedHashSet<>();
        for (String value : values) {
            parsed.add(audience(value));
        }
        if (parsed.size() != values.size()) {
            throw new AudienceException();
        }
        return List.copyOf(parsed);
    }

    private Audience audience(String value) {
        try {
            return Audience.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new AudienceException();
        }
    }

    private String idempotencyKey(Long targetId, String inputHash) {
        return "insight:issue:%d:%s:%s".formatted(
                targetId,
                inputHash.substring(0, 12),
                UUID.randomUUID());
    }

    private void validate(AgentInsightResponse response, AgentInsightRequest request) {
        if (response == null || response.meta() == null || response.insights() == null
                || !properties.getInsightPromptVersion().equals(response.meta().promptVersion())) {
            throw schemaViolation("meta 또는 promptVersion이 올바르지 않습니다.");
        }
        Set<String> requestedAudiences = Set.copyOf(request.audiences());
        Set<String> returnedAudiences = new HashSet<>();
        if (response.insights().size() != requestedAudiences.size()) {
            throw schemaViolation("요청한 audience 수와 응답 수가 다릅니다.");
        }
        Map<Long, Set<Integer>> sentenceIdsByFinding = new HashMap<>();
        request.findings().forEach(finding -> sentenceIdsByFinding.put(
                finding.id(),
                finding.sentences().stream()
                        .map(AgentInsightRequest.SentencePayload::id)
                        .collect(java.util.stream.Collectors.toSet())));

        for (AgentInsightResponse.Insight insight : response.insights()) {
            if (insight == null || !requestedAudiences.contains(insight.audience())
                    || !returnedAudiences.add(insight.audience())
                    || !StringUtils.hasText(insight.headline())
                    || insight.facts() == null
                    || insight.implications() == null
                    || insight.watchNext() == null
                    || insight.confidence() == null
                    || insight.confidence().compareTo(BigDecimal.ZERO) < 0
                    || insight.confidence().compareTo(BigDecimal.ONE) > 0) {
                throw schemaViolation("audience 인사이트 구조가 올바르지 않습니다.");
            }
            Set<String> factIds = validateFacts(insight.facts(), sentenceIdsByFinding);
            validateImplications(insight, factIds);
            if (insight.watchNext().stream().anyMatch(value -> !StringUtils.hasText(value))) {
                throw schemaViolation("watchNext는 빈 문자열일 수 없습니다.");
            }
        }
    }

    private Set<String> validateFacts(List<AgentInsightResponse.Fact> facts,
                                      Map<Long, Set<Integer>> sentenceIdsByFinding) {
        Set<String> factIds = new HashSet<>();
        Set<String> groundedFactIds = new HashSet<>();
        for (AgentInsightResponse.Fact fact : facts) {
            Set<Integer> knownSentenceIds = fact == null
                    ? null : sentenceIdsByFinding.get(fact.findingId());
            if (fact == null || !"FACT".equals(fact.claimType())
                    || !StringUtils.hasText(fact.id())
                    || !factIds.add(fact.id())
                    || !StringUtils.hasText(fact.text())
                    || knownSentenceIds == null
                    || fact.evidenceSentenceIds() == null
                    || fact.evidenceSentenceIds().isEmpty()
                    || new HashSet<>(fact.evidenceSentenceIds()).size()
                            != fact.evidenceSentenceIds().size()
                    || !knownSentenceIds.containsAll(fact.evidenceSentenceIds())
                    || !GROUNDEDNESS.contains(fact.groundedness())
                    || !StringUtils.hasText(fact.groundingReason())) {
                throw schemaViolation("FACT 구조 또는 evidence 참조가 올바르지 않습니다.");
            }
            if (!"ungrounded".equals(fact.groundedness())) {
                groundedFactIds.add(fact.id());
            }
        }
        return groundedFactIds;
    }

    private void validateImplications(AgentInsightResponse.Insight insight,
                                      Set<String> groundedFactIds) {
        Set<String> implicationIds = new HashSet<>();
        for (AgentInsightResponse.Implication implication : insight.implications()) {
            if (implication == null || !"IMPLICATION".equals(implication.claimType())
                    || !StringUtils.hasText(implication.id())
                    || !implicationIds.add(implication.id())
                    || !StringUtils.hasText(implication.text())
                    || implication.basisFactIds() == null
                    || implication.basisFactIds().isEmpty()
                    || new HashSet<>(implication.basisFactIds()).size()
                            != implication.basisFactIds().size()
                    || !groundedFactIds.containsAll(implication.basisFactIds())
                    || !StringUtils.hasText(implication.assumption())
                    || !StringUtils.hasText(implication.falsifiedBy())
                    || ("MARKET_INVESTOR".equals(insight.audience())
                            && containsInvestmentAdvice(implication.text()))) {
                throw schemaViolation("IMPLICATION 구조 또는 FACT 참조가 올바르지 않습니다.");
            }
        }
    }

    private boolean containsInvestmentAdvice(String value) {
        return value.contains("매수") || value.contains("매도") || value.contains("목표가");
    }

    private AgentClientException schemaViolation(String message) {
        return new AgentClientException("SCHEMA_VIOLATION", message);
    }

    private void recordSuccess(Long runId,
                               Long targetId,
                               AgentInsightRequest request,
                               AgentInsightResponse response,
                               LocalDateTime startedAt) {
        try {
            runRecorder.recordInsightSuccess(runId, targetId, request, response, startedAt);
        } catch (RuntimeException exception) {
            log.warn("인사이트 Agent 성공 감사 기록에 실패했습니다. targetId={} error={}",
                    targetId, exception.getMessage());
        }
    }

    private void recordFailure(Long runId,
                               Long targetId,
                               AgentInsightRequest request,
                               RuntimeException exception,
                               LocalDateTime startedAt) {
        AgentClientException clientException = exception instanceof AgentClientException value
                ? value : null;
        try {
            runRecorder.recordInsightFailure(
                    runId,
                    targetId,
                    request,
                    clientException == null ? "SCHEMA_VIOLATION" : clientException.getCode(),
                    exception.getMessage(),
                    clientException == null ? null : clientException.getUsage(),
                    timeoutPhase(clientException),
                    startedAt);
        } catch (RuntimeException recorderException) {
            log.warn("인사이트 Agent 실패 감사 기록에 실패했습니다. targetId={} error={}",
                    targetId, recorderException.getMessage());
        }
    }

    private void completeFailure(QuotaReservation reservation, RuntimeException exception) {
        AgentClientException clientException = exception instanceof AgentClientException value
                ? value : null;
        if (clientException == null) {
            quotaService.completeFailure(reservation, "SCHEMA_VIOLATION");
            return;
        }
        quotaService.completeFailure(reservation, clientException);
    }

    private AgentTimeoutPhase timeoutPhase(AgentClientException exception) {
        if (exception == null) {
            return null;
        }
        return switch (exception.getTimeoutPhase()) {
            case CONNECT -> AgentTimeoutPhase.CONNECT;
            case READ -> AgentTimeoutPhase.READ;
            case NONE -> null;
        };
    }
}
