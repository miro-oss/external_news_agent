package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.dto.AgentWeeklyReportRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWeeklyReportOrchestrator {
    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final WeeklyReportGenerator fallbackGenerator;
    private final AgentQuotaService quotaService;
    private final LlmPlanService planService;

    public ReportDocument generate(Long reportId, WeeklyReportInput input, LocalDateTime generatedAt) {
        if (!properties.isEnabled() || input.sources().stream().noneMatch(source ->
                source.structuredContent() != null && (!source.structuredContent().importantEvents().isEmpty()
                        || !source.structuredContent().watchItems().isEmpty()))) {
            return fallbackGenerator.generate(input);
        }
        QuotaReservation reservation = reserve(reportId);
        if (reservation == null) return fallbackGenerator.generate(input);
        AgentWeeklyReportRequest request = AgentWeeklyReportRequest.from(
                reservation.idempotencyKey(), reservation.plan(), reportId, input);
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentReportResponse response = null;
        try {
            response = client.weeklyReport(request);
            ReportDocument document = document(response, input);
            AgentReportResponse completed = response;
            safely(() -> recorder.recordWeeklyReportSuccess(request, completed, startedAt), "성공 감사 로그", reportId);
            safely(() -> quotaService.completeSuccess(reservation, completed.meta().credits()), "성공 quota 정산", reportId);
            return document;
        } catch (RuntimeException error) {
            AgentClientException failure = failure(error, response);
            AgentTimeoutPhase timeout = failure.getTimeoutPhase() == AgentClientException.TimeoutPhase.NONE
                    ? null : AgentTimeoutPhase.valueOf(failure.getTimeoutPhase().name());
            safely(() -> recorder.recordWeeklyReportFailure(request, failure.getCode(), failure.getMessage(),
                    failure.getUsage(), timeout, startedAt), "실패 감사 로그", reportId);
            safely(() -> quotaService.completeObservedFailure(reservation, failure), "실패 quota 정산", reportId);
            log.warn("주간 보고서 Agent 실패로 저장된 일일 내용의 fallback을 사용합니다. reportId={} code={}",
                    reportId, failure.getCode());
            return fallbackGenerator.generate(input);
        }
    }

    private QuotaReservation reserve(Long reportId) {
        AgentPlan plan = planService.resolveRunPlan(null);
        String key = "weekly-report:" + reportId;
        try {
            return quotaService.reserve(null, key, AgentTask.REPORT, plan);
        } catch (QuotaExceededException exhausted) {
            if (plan == AgentPlan.PAID && planService.paidExhaustedAction() == PaidExhaustedAction.FALLBACK_FREE) {
                try {
                    return quotaService.reserve(null, key + ":fallback-free", AgentTask.REPORT, AgentPlan.FREE);
                } catch (QuotaExceededException freeExhausted) {
                    log.warn("주간 보고서 FREE fallback quota도 소진됐습니다. reportId={}", reportId);
                }
            }
            return null;
        }
    }

    private ReportDocument document(AgentReportResponse response, WeeklyReportInput input) {
        if (response == null || response.meta() == null || response.executiveSummary() == null
                || response.executiveSummary().isEmpty() || response.executiveSummary().size() > 3
                || response.executiveSummary().stream().anyMatch(value -> !StringUtils.hasText(value))
                || response.importantEvents() == null || response.importantEvents().size() > 5
                || response.watchItems() == null || !StringUtils.hasText(response.markdownBody())) {
            throw invalid();
        }
        AgentReportResponse.Meta meta = response.meta();
        if (!Set.of("openai", "gemini", "mindlogic-claude", "mock").contains(meta.provider() == null ? "" : meta.provider())
                || !validText(meta.model(), NewsReport.MAX_MODEL_NAME_LENGTH) || !validText(meta.promptVersion(), 50)
                || meta.inputTokens() == null || meta.inputTokens() < 0 || meta.outputTokens() == null || meta.outputTokens() < 0
                || negative(meta.costUsd()) || negative(meta.credits())) throw invalid();
        Set<Long> allowed = new HashSet<>();
        input.sources().stream().filter(source -> source.structuredContent() != null).forEach(source -> {
            source.structuredContent().importantEvents().forEach(event -> allowed.addAll(event.sourceFindingIds()));
            source.structuredContent().watchItems().forEach(item -> allowed.addAll(item.sourceFindingIds()));
        });
        allowed.retainAll(input.sourceFindingIds());
        Set<Long> reflected = new LinkedHashSet<>();
        for (var event : response.importantEvents()) {
            if (event == null || !StringUtils.hasText(event.title()) || !StringUtils.hasText(event.summaryKo())
                    || !StringUtils.hasText(event.significance())) throw invalid();
            references(event.sourceFindingIds(), allowed, reflected);
        }
        for (var item : response.watchItems()) {
            if (item == null || !StringUtils.hasText(item.topic()) || !StringUtils.hasText(item.reason())) throw invalid();
            references(item.sourceFindingIds(), allowed, reflected);
        }
        ReportContent content = new ReportContent(response.executiveSummary(), response.importantEvents().stream()
                .map(event -> new ReportContent.ImportantEvent(event.title(), event.summaryKo(), event.significance(),
                        event.sourceFindingIds())).toList(), response.watchItems().stream()
                .map(item -> new ReportContent.WatchItem(item.topic(), item.reason(), item.sourceFindingIds())).toList(),
                input.sourceNotes());
        return new ReportDocument(input.title(), markdown(input.title(), content), meta.model(), meta.promptVersion(),
                meta.provider(), meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits(),
                meta.mock() ? ReportStatus.MOCK : ReportStatus.GENERATED, List.copyOf(reflected),
                input.sourceFindingIds().stream().filter(id -> !reflected.contains(id)).toList(), content);
    }

    private String markdown(String title, ReportContent content) {
        StringBuilder body = new StringBuilder("# ").append(ReportMarkdown.text(title))
                .append("\n\n## 이번 주 핵심\n\n");
        content.executiveSummary().forEach(summary -> body.append("- ").append(ReportMarkdown.text(summary)).append('\n'));
        body.append("\n## 주요 이슈와 주간 흐름\n\n");
        content.importantEvents().forEach(event -> {
            body.append("### ").append(ReportMarkdown.text(event.title())).append("\n\n")
                    .append(ReportMarkdown.text(event.summaryKo())).append("\n\n");
            event.significance().lines().forEach(line -> body.append("- ").append(ReportMarkdown.text(line)).append('\n'));
            body.append('\n');
        });
        body.append("## 후속 관찰\n\n");
        content.watchItems().forEach(item -> body.append("- **").append(ReportMarkdown.text(item.topic()))
                .append("**: ").append(ReportMarkdown.text(item.reason())).append('\n'));
        body.append("\n## 자료 범위\n\n");
        content.sourceNotes().forEach(note -> body.append("- ").append(ReportMarkdown.text(note)).append('\n'));
        return body.toString();
    }

    private void references(List<Long> values, Set<Long> allowed, Set<Long> reflected) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> !allowed.contains(value))) throw invalid();
        reflected.addAll(values);
    }

    private AgentClientException failure(RuntimeException error, AgentReportResponse response) {
        AgentReportResponse.Meta meta = response == null ? null : response.meta();
        AgentClientException.Usage usage = meta == null ? null : new AgentClientException.Usage(
                meta.inputTokens() == null || meta.inputTokens() < 0 ? null : meta.inputTokens(),
                meta.outputTokens() == null || meta.outputTokens() < 0 ? null : meta.outputTokens(),
                negative(meta.costUsd()) ? null : meta.costUsd(), negative(meta.credits()) ? null : meta.credits());
        if (error instanceof AgentClientException exception) {
            if (exception.getUsage() != null) return exception;
            if (usage == null) return exception;
            return new AgentClientException(exception.getCode(), exception.getMessage(), exception, usage,
                    exception.getTimeoutPhase(), exception.getExecutionMetadata());
        }
        return new AgentClientException("SCHEMA_VIOLATION", "주간 보고서 응답 계약을 검증하지 못했습니다.", error,
                usage, AgentClientException.TimeoutPhase.NONE);
    }

    private boolean validText(String value, int bytes) {
        return StringUtils.hasText(value) && value.getBytes(StandardCharsets.UTF_8).length <= bytes;
    }
    private boolean negative(BigDecimal value) { return value == null || value.signum() < 0; }
    private AgentClientException invalid() { return new AgentClientException("SCHEMA_VIOLATION", "주간 보고서 응답 계약 위반입니다."); }
    private void safely(Runnable operation, String label, Long reportId) {
        try { operation.run(); }
        catch (RuntimeException error) { log.error("주간 보고서 {} 실패. reportId={}", label, reportId, error); }
    }
}
