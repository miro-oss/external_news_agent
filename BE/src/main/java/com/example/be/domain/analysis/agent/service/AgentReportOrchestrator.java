package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportGenerator;
import com.example.be.domain.reports.service.ReportSourceStats;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentReportOrchestrator {

    private static final String DEFAULT_PERSPECTIVE = "TECHNOLOGY";
    private static final Set<String> PROVIDER_VALUES = Set.of("gemini", "mindlogic-claude", "mock");

    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final ReportGenerator fallbackGenerator;

    public ReportDocument generate(CollectionRun run,
                                   List<Finding> findings,
                                   LocalDateTime generatedAt) {
        ReportSourceStats sourceStats = sourceStats(run, findings);
        if (!properties.isEnabled()) {
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }

        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentReportRequest request = null;
        try {
            request = request(run, findings, generatedAt, sourceStats);
            AgentReportResponse response = client.report(request);
            ReportDocument document = toDocument(response, request);
            recordSuccessSafely(run.getId(), request, response, startedAt);
            return document;
        } catch (RuntimeException exception) {
            String code = exception instanceof AgentClientException clientException
                    ? clientException.getCode()
                    : "SCHEMA_VIOLATION";
            if (request != null) {
                recordFailureSafely(run.getId(), request, code, exception.getMessage(), startedAt);
            }
            log.warn("Agent 보고서 생성에 실패해 안전한 fallback을 사용한다. runId={} code={} error={}",
                    run.getId(), code, exception.getMessage());
            return fallbackGenerator.generate(findings, generatedAt, sourceStats);
        }
    }

    private AgentReportRequest request(CollectionRun run,
                                       List<Finding> findings,
                                       LocalDateTime generatedAt,
                                       ReportSourceStats sourceStats) {
        List<Finding> eligible = findings.stream()
                .filter(finding -> finding.getAnalysisSource() != AnalysisSource.STUB)
                .toList();
        LocalDateTime finishedAt = run.getFinishedAt() == null ? generatedAt : run.getFinishedAt();
        return new AgentReportRequest(
                "run:" + run.getId() + ":report",
                properties.getDefaultPlan(),
                new AgentReportRequest.RunPayload(
                        run.getId(),
                        toOffset(run.getStartedAt()),
                        toOffset(finishedAt),
                        topics(run, findings)),
                eligible.stream().map(this::findingPayload).toList(),
                List.of(),
                new AgentReportRequest.SourceStatsPayload(
                        sourceStats.collected(),
                        sourceStats.blocked(),
                        sourceStats.failed(),
                        sourceStats.paywalled(),
                        sourceStats.stubExcluded()),
                DEFAULT_PERSPECTIVE);
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
                finding.getSummary(),
                finding.getEffectiveKeyPoints().stream().map(point -> point.text()).toList(),
                finding.getIntent(),
                finding.getSentiment().toApiValue(),
                finding.getRiskLevel().toApiValue(),
                finding.getRelevance().toApiValue(),
                finding.getCategory(),
                fetchStatus == null ? FetchStatus.METADATA_ONLY.name() : fetchStatus.name());
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
                ReportStatus.GENERATED);
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
        int paywalled = countStatus(findings, FetchStatus.FULLTEXT_BLOCKED);
        int blocked = paywalled + countStatus(findings, FetchStatus.ROBOTS_DISALLOWED);
        int failed = countStatus(findings, FetchStatus.FETCH_FAILED);
        int stubExcluded = (int) findings.stream()
                .filter(finding -> finding.getAnalysisSource() == AnalysisSource.STUB)
                .count();
        List<CollectionRunItem> items = run.getItems();
        int collected = items == null || items.isEmpty()
                ? run.getScannedCount()
                : items.stream().mapToInt(CollectionRunItem::getScannedCount).sum();
        return new ReportSourceStats(collected, blocked, failed, paywalled, stubExcluded);
    }

    private int countStatus(List<Finding> findings, FetchStatus status) {
        return (int) findings.stream()
                .filter(finding -> finding.getArticle().getFetchStatus() == status)
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
                                     LocalDateTime startedAt) {
        try {
            recorder.recordReportFailure(runId, request, code, message, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 보고서의 감사 로그를 기록하지 못했다. runId={} code={}",
                    runId, code, exception);
        }
    }
}
