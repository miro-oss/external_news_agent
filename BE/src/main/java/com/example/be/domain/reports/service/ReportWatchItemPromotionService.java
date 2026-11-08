package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.dto.res.ReportWatchItemPromotionResult;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.ReportWatchItemKey;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportWatchItemPromotionService {
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final ReportEventFeedbackProjection projection;

    /**
     * Serializes an exact, still-visible watch selection with feedback and other promotions.
     * Repeated selections succeed only while the promoted event remains eligible for public display.
     */
    @Transactional
    public ReportWatchItemPromotionResult promote(long reportId, ReportWatchItemPromotionRequest request) {
        if (reportId < 1 || request == null || request.topic() == null || request.topic().isBlank()
                || request.reason() == null || request.reason().isBlank() || request.sourceFindingIds() == null
                || request.sourceFindingIds().isEmpty()
                || request.sourceFindingIds().stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(request.sourceFindingIds()).size() != request.sourceFindingIds().size()) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST);
        }
        // Serialize against other promotions and feedback submissions on this report.
        var report = reports.findByIdForUpdate(reportId)
                .filter(item -> item.getDeletedAt() == null && item.getReportStatus() != ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        var stored = report.getStructuredContent();
        if (stored == null) throw conflict();
        var target = new ReportContent.WatchItem(request.topic(), request.reason(), request.sourceFindingIds());
        int index = stored.watchItems().indexOf(target);
        if (index < 0) throw conflict();
        var visible = ReportFindings.loadVisible(report, findings, relevance);
        var allowed = visible.findings().stream().map(Finding::getId).collect(Collectors.toSet());
        if (!allowed.containsAll(target.sourceFindingIds())) throw conflict();
        var view = projection.project(report, visible, projection.reviews(reportId));
        var current = view.readingContent().structuredContent();
        if (current == null || target.sourceFindingIds().stream().anyMatch(view.excludedEvidenceFindingIds()::contains)) {
            throw conflict();
        }
        if (report.getPromotedWatchItemKeys().contains(ReportWatchItemKey.of(target))) {
            // Retrying a saved selection is safe only while that event is still publicly visible.
            if (current.importantEvents().stream().noneMatch(event -> ReportWatchItemPromotions.matches(event, target))) {
                throw conflict();
            }
        } else {
            if (!current.watchItems().contains(target)) throw conflict();
            report.promoteWatchItem(index);
        }
        return new ReportWatchItemPromotionResult(reportId);
    }

    private static GeneralException conflict() { return new GeneralException(GeneralErrorCode.CONFLICT); }
}
