package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentReportOrchestrator {

    private static final int MAX_REPORT_FINDINGS = 50;
    private static final Set<String> PROVIDER_VALUES = Set.of("gemini", "mindlogic-claude", "mock");

    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final ReportGenerator fallbackGenerator;
    private final CollectionRunArticleRepository observationRepository;
    private final AgentQuotaService quotaService;
    private final LlmPlanService planService;
    private final CollectionResultWriter resultWriter;

    public ReportDocument generate(CollectionRun run,
                                   List<Finding> findings,
                                   LocalDateTime generatedAt) {
        ReportSourceStats sourceStats = sourceStats(run, findings);
        if (!properties.isEnabled()) {
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }
        List<Finding> eligible = eligibleFindings(findings);
        if (eligible.isEmpty()) {
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }

        ReservationSelection selection = reserve(run);
        if (selection == null) {
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }

        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentReportRequest request = null;
        try {
            request = request(
                    run,
                    eligible,
                    generatedAt,
                    sourceStats,
                    selection.plan(),
                    selection.reservation().idempotencyKey());
            AgentReportResponse response = client.report(request);
            ReportDocument document = toDocument(response, request);
            recordSuccessSafely(run.getId(), request, response, startedAt);
            completeSuccessSafely(selection.reservation(), response.meta().credits());
            return document;
        } catch (RuntimeException exception) {
            AgentClientException clientException = exception instanceof AgentClientException value
                    ? value
                    : null;
            String code = clientException != null
                    ? clientException.getCode()
                    : "SCHEMA_VIOLATION";
            AgentClientException.Usage usage = failureUsage(clientException, selection.reservation());
            if (request != null) {
                recordFailureSafely(
                        run.getId(), request, code, exception.getMessage(), usage,
                        timeoutPhase(clientException), startedAt);
            }
            completeFailureSafely(selection.reservation(), exception, code);
            log.warn("Agent 보고서 생성에 실패해 안전한 fallback을 사용한다. runId={} code={} error={}",
                    run.getId(), code, exception.getMessage());
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }
    }

    private AgentReportRequest request(CollectionRun run,
                                       List<Finding> findings,
                                       LocalDateTime generatedAt,
                                       ReportSourceStats sourceStats,
                                       AgentPlan plan,
                                       String idempotencyKey) {
        LocalDateTime finishedAt = run.getFinishedAt() == null ? generatedAt : run.getFinishedAt();
        return new AgentReportRequest(
                idempotencyKey,
                plan,
                new AgentReportRequest.RunPayload(
                        run.getId(),
                        toOffset(run.getStartedAt()),
                        toOffset(finishedAt),
                        topics(run, findings)),
                findings.stream().map(this::findingPayload).toList(),
                List.of(),
                new AgentReportRequest.SourceStatsPayload(
                        sourceStats.collected(),
                        sourceStats.blocked(),
                        sourceStats.failed(),
                        sourceStats.paywalled(),
                        sourceStats.stubExcluded()),
                ReportSourceNotes.from(sourceStats));
    }

    private ReservationSelection reserve(CollectionRun run) {
        AgentPlan requestedPlan = run.getLlmPlan();
        String idempotencyKey = "run:" + run.getId() + ":report";
        try {
            return new ReservationSelection(
                    requestedPlan,
                    quotaService.reserve(run.getId(), idempotencyKey, AgentTask.REPORT, requestedPlan));
        } catch (QuotaExceededException exhausted) {
            if (requestedPlan == AgentPlan.PAID
                    && planService.paidExhaustedAction() == PaidExhaustedAction.FALLBACK_FREE) {
                try {
                    QuotaReservation reservation = quotaService.reserve(
                            run.getId(), idempotencyKey + ":fallback-free", AgentTask.REPORT, AgentPlan.FREE);
                    addAgentWarning(run.getId(),
                            CollectionRunWarning.CODE_LLM_FALLBACK_FREE,
                            "PAID quota가 소진되어 보고서를 FREE 플랜으로 생성합니다.");
                    return new ReservationSelection(AgentPlan.FREE, reservation);
                } catch (QuotaExceededException freeExhausted) {
                    log.warn("보고서 FREE fallback quota도 소진됐다. runId={}", run.getId());
                }
            }
            addAgentWarning(run.getId(),
                    CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED,
                    "LLM quota가 소진되어 안전한 fallback 보고서를 생성합니다.");
            return null;
        }
    }

    private void addAgentWarning(Long runId, String code, String message) {
        try {
            resultWriter.addAgentWarning(runId, code, message);
        } catch (RuntimeException exception) {
            log.error("보고서 LLM quota 경고를 기록하지 못했다. runId={} code={}",
                    runId, code, exception);
        }
    }

    private void completeSuccessSafely(QuotaReservation reservation, BigDecimal credits) {
        try {
            quotaService.completeSuccess(reservation, credits);
        } catch (RuntimeException exception) {
            log.error("보고서 Agent 성공 quota 예약을 정산하지 못했다. reservationId={}",
                    reservation.id(), exception);
        }
    }

    private void completeFailureSafely(QuotaReservation reservation,
                                       RuntimeException exception,
                                       String code) {
        try {
            if (exception instanceof AgentClientException clientException) {
                quotaService.completeFailure(reservation, clientException);
            } else {
                quotaService.completeFailure(reservation, code);
            }
        } catch (RuntimeException completionError) {
            log.error("보고서 Agent 실패 quota 예약을 정산하지 못했다. reservationId={}",
                    reservation.id(), completionError);
        }
    }

    private AgentClientException.Usage failureUsage(AgentClientException exception,
                                                     QuotaReservation reservation) {
        if (exception == null || exception.getUsage() != null || !exception.isReadTimeout()) {
            return exception == null ? null : exception.getUsage();
        }
        return new AgentClientException.Usage(null, null, null, reservation.reservedUnits());
    }

    private AgentTimeoutPhase timeoutPhase(AgentClientException exception) {
        if (exception == null || exception.getTimeoutPhase() == AgentClientException.TimeoutPhase.NONE) {
            return null;
        }
        return AgentTimeoutPhase.valueOf(exception.getTimeoutPhase().name());
    }

    private AgentReportRequest.FindingPayload findingPayload(Finding finding) {
        FetchStatus fetchStatus = finding.getArticle().getFetchStatus();
        return new AgentReportRequest.FindingPayload(
                finding.getId(),
                finding.getArticle().getId(),
                finding.getArticle().getTitle(),
                finding.getArticle().getCanonicalUrl(),
                finding.getArticle().getSourceName(),
                finding.getChangeType().name(),
                ReportEvidencePolicy.reportSummary(finding),
                ReportEvidencePolicy.supportedKeyPoints(finding).stream()
                        .map(point -> new AgentReportRequest.KeyPointPayload(
                                point.text(), point.evidence(), point.groundedness()))
                        .toList(),
                finding.getIntent(),
                finding.getSentiment().toApiValue(),
                finding.getRiskLevel().toApiValue(),
                finding.getRelevance().toApiValue(),
                finding.getCategory(),
                fetchStatus == null ? FetchStatus.METADATA_ONLY.name() : fetchStatus.name());
    }

    private List<Finding> eligibleFindings(List<Finding> findings) {
        return ReportFindingOrder.sort(findings.stream()
                        .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                        .filter(ReportEvidencePolicy::hasSupportedEvidence)
                        .toList())
                .stream()
                .limit(MAX_REPORT_FINDINGS)
                .toList();
    }

    private ReportDocument toDocument(AgentReportResponse response, AgentReportRequest request) {
        validate(response, request);
        AgentReportResponse.Meta meta = response.meta();
        return new ReportDocument(
                response.title().trim(),
                response.markdownBody(),
                meta.model(),
                meta.promptVersion(),
                meta.provider(),
                meta.inputTokens(),
                meta.outputTokens(),
                meta.costUsd(),
                meta.credits(),
                meta.mock() ? ReportStatus.MOCK : ReportStatus.GENERATED);
    }

    private void validate(AgentReportResponse response, AgentReportRequest request) {
        if (!StringUtils.hasText(response.title())
                || response.title().getBytes(StandardCharsets.UTF_8).length > NewsReport.MAX_TITLE_LENGTH
                || !StringUtils.hasText(response.markdownBody())
                || response.executiveSummary() == null || response.executiveSummary().isEmpty()
                || hasBlank(response.executiveSummary())
                || response.importantEvents() == null
                || response.watchItems() == null
                || response.sourceNotes() == null || response.sourceNotes().isEmpty()
                || hasBlank(response.sourceNotes())
                || response.meta() == null) {
            throw schemaViolation("Agent 보고서 응답의 필수 필드가 없습니다.");
        }

        Set<Long> findingIds = new HashSet<>(request.findings().stream()
                .map(AgentReportRequest.FindingPayload::id)
                .toList());
        response.importantEvents().forEach(event -> {
            if (event == null
                    || !StringUtils.hasText(event.title())
                    || !StringUtils.hasText(event.summaryKo())
                    || !StringUtils.hasText(event.significance())) {
                throw schemaViolation("Agent important event가 올바르지 않습니다.");
            }
            validateReferences(event.sourceFindingIds(), findingIds);
        });
        response.watchItems().forEach(item -> {
            if (item == null
                    || !StringUtils.hasText(item.topic())
                    || !StringUtils.hasText(item.reason())) {
                throw schemaViolation("Agent watch item이 올바르지 않습니다.");
            }
            validateReferences(item.sourceFindingIds(), findingIds);
        });
        validateMeta(response.meta());
    }

    private void validateReferences(List<Long> references, Set<Long> findingIds) {
        if (references == null || references.isEmpty()
                || references.stream().anyMatch(reference -> !findingIds.contains(reference))) {
            throw schemaViolation("Agent 보고서가 존재하지 않는 finding을 참조합니다.");
        }
    }

    private void validateMeta(AgentReportResponse.Meta meta) {
        if (!StringUtils.hasText(meta.provider())
                || !PROVIDER_VALUES.contains(meta.provider())
                || !StringUtils.hasText(meta.model())
                || meta.model().getBytes(StandardCharsets.UTF_8).length > NewsReport.MAX_MODEL_NAME_LENGTH
                || !StringUtils.hasText(meta.promptVersion())
                || meta.promptVersion().getBytes(StandardCharsets.UTF_8).length > 50
                || isNegative(meta.inputTokens())
                || isNegative(meta.outputTokens())
                || isNegative(meta.costUsd())
                || isNegative(meta.credits())) {
            throw schemaViolation("Agent 보고서 meta가 올바르지 않습니다.");
        }
    }

    private ReportSourceStats sourceStats(CollectionRun run, List<Finding> findings) {
        Map<Long, FetchStatus> statusesByArticle = new LinkedHashMap<>();
        observationRepository.findArticleFetchStatusesByRunId(run.getId()).forEach(observation ->
                statusesByArticle.put(observation.getArticleId(), observation.getFetchStatus()));
        int paywalled = countStatus(statusesByArticle, FetchStatus.FULLTEXT_BLOCKED);
        int blocked = paywalled + countStatus(statusesByArticle, FetchStatus.ROBOTS_DISALLOWED);
        int failed = countStatus(statusesByArticle, FetchStatus.FETCH_FAILED);
        int stubExcluded = (int) findings.stream()
                .filter(finding -> finding.getAnalysisSource() == AnalysisSource.STUB)
                .count();
        int evidenceExcluded = (int) findings.stream()
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(finding -> !ReportEvidencePolicy.hasSupportedEvidence(finding))
                .count();
        List<CollectionRunItem> items = run.getItems();
        int collected = items == null || items.isEmpty()
                ? run.getScannedCount()
                : items.stream().mapToInt(CollectionRunItem::getScannedCount).sum();
        return new ReportSourceStats(
                collected, blocked, failed, paywalled, stubExcluded, evidenceExcluded);
    }

    private int countStatus(Map<Long, FetchStatus> statusesByArticle, FetchStatus status) {
        return (int) statusesByArticle.values().stream()
                .filter(status::equals)
                .count();
    }

    private List<String> topics(CollectionRun run, List<Finding> findings) {
        List<String> fromItems = run.getItems() == null ? List.of() : run.getItems().stream()
                .map(CollectionRunItem::getTopic)
                .filter(topic -> topic != null && StringUtils.hasText(topic.getName()))
                .map(topic -> topic.getName().trim())
                .distinct()
                .toList();
        if (!fromItems.isEmpty()) {
            return fromItems;
        }
        return findings.stream()
                .map(finding -> finding.getArticle().getTopic())
                .filter(topic -> topic != null && StringUtils.hasText(topic.getName()))
                .map(topic -> topic.getName().trim())
                .distinct()
                .toList();
    }

    private boolean hasBlank(List<String> values) {
        return values.stream().anyMatch(value -> !StringUtils.hasText(value));
    }

    private boolean isNegative(Long value) {
        return value == null || value < 0;
    }

    private boolean isNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        if (value == null) {
            throw schemaViolation("보고서 run 시각이 없습니다.");
        }
        return value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private AgentClientException schemaViolation(String message) {
        return new AgentClientException("SCHEMA_VIOLATION", message);
    }

    private void recordSuccessSafely(Long runId,
                                     AgentReportRequest request,
                                     AgentReportResponse response,
                                     LocalDateTime startedAt) {
        try {
            recorder.recordReportSuccess(runId, request, response, startedAt);
        } catch (RuntimeException exception) {
            log.error("성공한 Agent 보고서의 감사 로그를 기록하지 못했다. runId={}", runId, exception);
        }
    }

    private void recordFailureSafely(Long runId,
                                     AgentReportRequest request,
                                     String code,
                                     String message,
                                     AgentClientException.Usage usage,
                                     AgentTimeoutPhase timeoutPhase,
                                     LocalDateTime startedAt) {
        try {
            recorder.recordReportFailure(
                    runId, request, code, message, usage, timeoutPhase, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 보고서의 감사 로그를 기록하지 못했다. runId={} code={}",
                    runId, code, exception);
        }
    }

    private record ReservationSelection(AgentPlan plan, QuotaReservation reservation) {
    }
}
