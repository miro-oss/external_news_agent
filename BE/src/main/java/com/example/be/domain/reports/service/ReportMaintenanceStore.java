package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Short transactions surround the provider call. Report/run locks share the normal writer order. */
@Service
@RequiredArgsConstructor
public class ReportMaintenanceStore {
    private final NewsReportRepository reports;
    private final CollectionRunRepository runs;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final ReportEventFeedbackProjection feedback;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public Input read(long reportId) {
        return input(requireReport(reports.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("완료된 보고서를 찾을 수 없습니다."))));
    }

    @Transactional
    public ReportMaintenanceService.Snapshot promote(long reportId, String expectedFingerprint,
                                                       String exactTitle, int briefingIndex) {
        var input = input(lockedReport(reportId));
        requireFingerprint(input, expectedFingerprint);
        var content = ReportMaintenanceContent.promote(input.report().getStructuredContent(), input.view(),
                exactTitle, briefingIndex);
        input.report().reviseReadingContent(ReportEventFeedbackProjection.markdown(content), content);
        return snapshot(input.report(), feedback.reviews(reportId));
    }

    @Transactional
    public ReportMaintenanceService.Snapshot replace(long reportId, String expectedFingerprint,
                                                      ReportDocument generated, String exactTitle, int briefingIndex) {
        var input = input(lockedReport(reportId));
        requireFingerprint(input, expectedFingerprint);
        var report = input.report();
        if (report.getReportStatus() != ReportStatus.FALLBACK || generated.status() != ReportStatus.GENERATED)
            throw new IllegalStateException("대체 보고서를 검증된 AI 생성 결과로만 교체할 수 있습니다.");
        // Recheck current evidence and feedback under the write lock, after the provider returned.
        var supported = supportedView(input);
        var document = ReportMaintenanceContent.retainAndPromote(generated, report.getStructuredContent(),
                supported, exactTitle, briefingIndex);
        report.invalidateComparisonInput();
        report.complete(report.getTitle(), document.markdownBody(), document.modelName(), document.promptVersion(),
                document.llmProvider(), document.inputTokens(), document.outputTokens(), document.costUsd(),
                document.credits(), document.reflectedFindingIds(), document.excludedFindingIds(),
                document.status(), report.getGeneratedAt());
        report.recordStructuredContent(document.structuredContent());
        // No ReportCompleted event, automatic insight request, delivery or company relinking.
        return snapshot(report, feedback.reviews(reportId));
    }

    static ReportEventFeedbackProjection.View supportedView(Input input) {
        var view = input.view();
        var supported = view.findings().stream()
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence)
                .filter(finding -> !view.excludedEvidenceFindingIds().contains(finding.getId())).toList();
        return new ReportEventFeedbackProjection.View(supported, view.readingContent(), view.excludedEvents(),
                view.excludedEvidenceFindingIds(), view.sourceFiltered());
    }

    static void requireFingerprint(Input input, String expected) {
        if (expected == null || !expected.equals(input.snapshot().fingerprint()))
            throw new IllegalStateException("보고서 내용이나 피드백이 변경되었습니다. 복구 계획을 다시 확인하세요.");
    }

    private NewsReport lockedReport(long reportId) {
        Long runId = reports.findRunIdById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("실행별 보고서를 찾을 수 없습니다."));
        runs.findByIdForUpdate(runId).orElseThrow(() -> new IllegalArgumentException("수집 실행을 찾을 수 없습니다."));
        return requireReport(reports.findByIdForUpdate(reportId)
                .orElseThrow(() -> new IllegalArgumentException("보고서를 찾을 수 없습니다.")));
    }

    private NewsReport requireReport(NewsReport report) {
        if (report.getDeletedAt() != null || report.getReportStatus() == ReportStatus.PENDING
                || report.getReportScope() != ReportScope.RUN || report.getRun() == null)
            throw new IllegalArgumentException("완료된 실행별 보고서만 편집하거나 복구할 수 있습니다.");
        if (report.getRun().getStatus() == null || report.getRun().getStatus().isInProgress())
            throw new IllegalStateException("수집 실행이 끝난 후 보고서를 복구할 수 있습니다.");
        // Provider preparation occurs outside this transaction; initialize its frozen collection scope.
        report.getRun().getItems().forEach(item -> {
            var topic = item.collectionTopic();
            if (topic != null) topic.getName();
        });
        return report;
    }

    private Input input(NewsReport report) {
        var reviews = feedback.reviews(report.getId());
        var visible = ReportFindings.loadVisible(report, findings, relevance);
        return new Input(report, feedback.project(report, visible, reviews), snapshot(report, reviews));
    }

    private ReportMaintenanceService.Snapshot snapshot(NewsReport report, List<Feedback> reviews) {
        // Finding analyses intentionally remain outside this revision: restoring a STUB must not
        // invalidate the user's prior editorial selection. All mutable report decisions are included.
        byte[] bytes = mapper.writeValueAsBytes(Arrays.asList(report.getId(), report.getRunId(), report.getReportScope(),
                report.getTitle(), report.getGeneratedAt(), report.getMarkdownBody(), report.getStructuredContent(),
                report.getReportStatus(), report.getModelName(), report.getPromptVersion(), report.getLlmProvider(),
                report.getInputTokens(), report.getOutputTokens(), report.getCostUsd(), report.getCredits(),
                report.getReflectedFindingIds(), report.getExcludedFindingIds(), report.getCompanySuppressedFindingIds(),
                report.isCompanySelectionRecorded(), report.getCollectionContexts(), report.getPromotedWatchItemKeys(),
                report.getReportCoverageSnapshot(), report.getReportKeywordSnapshot(), report.getReportIssueCompanySnapshot(),
                reviews));
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new ReportMaintenanceService.Snapshot(report.getId(), report.getRunId(), fingerprint,
                report.getReportStatus(), report.getGeneratedAt(), report.getStructuredContent() == null ? List.of()
                : report.getStructuredContent().importantEvents().stream().map(event -> event.title()).toList());
    }

    public record Input(NewsReport report, ReportEventFeedbackProjection.View view,
                        ReportMaintenanceService.Snapshot snapshot) { }
}
