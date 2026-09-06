package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private final Object[] generationLocks = IntStream.range(0, 256)
            .mapToObj(ignored -> new Object())
            .toArray(Object[]::new);

    public InsightDTO.Result create(InsightDTO.CreateRequest createRequest) {
        AgentTargetType targetType = targetType(createRequest == null
                ? null : createRequest.targetType());
        Long targetId = targetId(createRequest == null ? null : createRequest.targetId());
        List<Audience> audiences = audiences(createRequest == null
                ? null : createRequest.audiences());
        if (!properties.isEnabled()) {
            throw new GeneralException(
                    GeneralErrorCode.CONFLICT,
                    "관점 인사이트 기능이 현재 비활성화되어 있습니다.");
        }
        InsightInputAssembler.Snapshot snapshot = inputAssembler.assemble(targetId);
        GenerationKey generationKey = new GenerationKey(
                targetType,
                targetId,
                snapshot.inputHash(),
                properties.getInsightPromptVersion());
        Object lock = generationLocks[Math.floorMod(
                generationKey.hashCode(), generationLocks.length)];
        synchronized (lock) {
            return createUnderLock(targetType, targetId, audiences, snapshot);
        }
    }

    private InsightDTO.Result createUnderLock(AgentTargetType targetType,
                                               Long targetId,
                                               List<Audience> audiences,
                                               InsightInputAssembler.Snapshot snapshot) {
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
        AgentPlan plan = planService.get().plan();
        String idempotencyKey = idempotencyKey(
                targetType,
                targetId,
                snapshot.inputHash(),
                properties.getInsightPromptVersion(),
                missing);
        QuotaReservation reservation;
        try {
            reservation = quotaService.reserveInsight(
                    snapshot.runId(), idempotencyKey, plan);
        } catch (QuotaExceededException exception) {
            throw new LlmException(LlmErrorCode.QUOTA_EXHAUSTED, Map.of(
                    "plan", exception.getPlan().name(),
                    "reason", exception.getMessage()));
        } catch (IllegalStateException exception) {
            List<NewsInsight> concurrentlySaved = persistenceService.findCached(
                    targetType,
                    targetId,
                    snapshot.inputHash(),
                    properties.getInsightPromptVersion(),
                    audiences);
            if (concurrentlySaved.size() == audiences.size()) {
                concurrentlySaved.forEach(
                        insight -> byAudience.put(insight.getAudience(), insight));
                return result(true, targetType, targetId, snapshot.inputHash(),
                        properties.getInsightPromptVersion(), audiences, byAudience);
            }
            throw new GeneralException(
                    GeneralErrorCode.CONFLICT,
                    "동일한 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.");
        }

        AgentInsightRequest request = new AgentInsightRequest(
                reservation.idempotencyKey(),
                plan,
                missing.stream().map(Enum::name).toList(),
                new AgentInsightRequest.TargetPayload(targetType.name(), targetId),
                snapshot.topic(),
                snapshot.findings());
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentInsightResponse response = null;
        List<NewsInsight> saved;
        boolean validated = false;
        try {
            response = agentClient.insight(request);
            validate(response, request);
            validated = true;
            saved = persistenceService.saveGenerated(
                    targetType,
                    targetId,
                    snapshot.inputHash(),
                    response,
                    snapshot.articleIdsByFinding());
        } catch (RuntimeException exception) {
            RuntimeException recordedException = validated
                    ? persistenceFailure(exception, response)
                    : exception;
            recordFailure(snapshot.runId(), targetId, request, recordedException, startedAt);
            if (validated) {
                quotaService.completeFailure(reservation, "SCHEMA_VIOLATION");
            } else {
                completeFailure(reservation, exception);
            }
            throw new GeneralException(
                    GeneralErrorCode.INTERNAL_SERVER_ERROR,
                    "관점 인사이트 생성에 실패했습니다.");
        }

        recordSuccess(snapshot.runId(), targetId, request, response, startedAt);
        quotaService.completeSuccess(reservation, response.meta().credits());
        saved.forEach(insight -> byAudience.put(insight.getAudience(), insight));
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

    private String idempotencyKey(AgentTargetType targetType,
                                  Long targetId,
                                  String inputHash,
                                  String promptVersion,
                                  List<Audience> missing) {
        String audienceKey = missing.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
        return "insight:%s:%d:%s:%s:%s".formatted(
                targetType.name(),
                targetId,
                inputHash,
                promptVersion,
                audienceKey);
    }

    private void validate(AgentInsightResponse response, AgentInsightRequest request) {
        if (response == null || response.meta() == null || response.insights() == null
                || response.meta().truncated()
                || !properties.getInsightPromptVersion().equals(response.meta().promptVersion())) {
            throw schemaViolation("meta, promptVersion 또는 truncated 상태가 올바르지 않습니다.");
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
            if ("MARKET_INVESTOR".equals(insight.audience())
                    && containsInvestmentAdvice(insight)) {
                throw schemaViolation("MARKET_INVESTOR 인사이트에 투자 자문 표현이 있습니다.");
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

    private boolean containsInvestmentAdvice(AgentInsightResponse.Insight insight) {
        return containsInvestmentAdvice(insight.headline())
                || insight.facts().stream()
                        .map(AgentInsightResponse.Fact::text)
                        .anyMatch(this::containsInvestmentAdvice)
                || insight.implications().stream()
                        .anyMatch(implication -> containsInvestmentAdvice(implication.text())
                                || containsInvestmentAdvice(implication.assumption())
                                || containsInvestmentAdvice(implication.falsifiedBy()))
                || insight.watchNext().stream().anyMatch(this::containsInvestmentAdvice);
    }

    private AgentClientException persistenceFailure(RuntimeException exception,
                                                     AgentInsightResponse response) {
        AgentInsightResponse.Meta meta = response.meta();
        return new AgentClientException(
                "PERSISTENCE_FAILED",
                "인사이트 저장에 실패했습니다.",
                exception,
                new AgentClientException.Usage(
                        meta.inputTokens(),
                        meta.outputTokens(),
                        meta.costUsd(),
                        meta.credits()));
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

    private record GenerationKey(AgentTargetType targetType,
                                 Long targetId,
                                 String inputHash,
                                 String promptVersion) {
    }
}
