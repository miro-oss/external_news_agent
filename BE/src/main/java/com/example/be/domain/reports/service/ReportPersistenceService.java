package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.ReportCompleted;
import org.springframework.context.ApplicationEventPublisher;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportPersistenceService {

    private static final String PENDING_TITLE = "보고서 생성 중";
    private static final String PENDING_BODY = "보고서 생성이 진행 중입니다.";
    private static final String PENDING_MODEL = "pending-report-v1";

    private final CollectionRunRepository runRepository;
    private final NewsReportRepository reportRepository;
    private final ReportNotificationAutomationService notificationAutomation;
    private final CollectionRunArticleRepository observationRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public Reservation reserve(Long runId, LocalDateTime generatedAt) {
        CollectionRun run = lockedRun(runId);
        NewsReport existing = reportRepository.findByRunId(runId).orElse(null);
        if (existing != null) {
            run.attachReport(existing.getId());
            return new Reservation(existing.getId(), false);
        }
        // 화면의 신규 기사 통계와 같은 NEW 관측을 기준으로 한다. 기존 기사 변경·재분석·경고는
        // 수집 이력에 남기되 보고서 생성 사유로 사용하지 않는다. 집계는 finish 전이라 관측을 조회한다.
        if (!observationRepository.existsByRunIdAndChangeTypeIn(runId, List.of(ChangeType.NEW))) {
            return new Reservation(null, false);
        }

        NewsReport report = reportRepository.save(NewsReport.builder()
                .run(run)
                .collectionContexts(java.util.List.of(ReportCollectionContext.from(run)))
                .title(PENDING_TITLE)
                .markdownBody(PENDING_BODY)
                .modelName(PENDING_MODEL)
                .reportStatus(ReportStatus.PENDING)
                .generatedAt(generatedAt)
                .build());
        run.attachReport(report.getId());
        return new Reservation(report.getId(), true);
    }

    @Transactional
    public Long completeRecovered(Long reportId, ReportDocument document, LocalDateTime generatedAt) {
        NewsReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new IllegalStateException("복구할 보고서가 없습니다. reportId=" + reportId));
        if (report.getReportStatus() != ReportStatus.PENDING) return report.getId();
        // Flag travels in the report completion write itself, without a separate fallible comparison DB write.
        report.invalidateComparisonInput();
        return complete(reportId, document, generatedAt);
    }

    @Transactional
    public Long complete(Long reportId, ReportDocument document, LocalDateTime generatedAt) {
        NewsReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new IllegalStateException("완료할 보고서가 없습니다. reportId=" + reportId));
        if (report.getReportStatus() != ReportStatus.PENDING) {
            return report.getId();
        }
        String title = ReportTitles.forReport(report, document.title(), generatedAt);
        report.complete(
                title,
                document.title().equals(title) ? document.markdownBody()
                        : ReportTitles.alignMarkdownTitle(document.markdownBody(), title),
                document.modelName(),
                document.promptVersion(),
                document.llmProvider(),
                document.inputTokens(),
                document.outputTokens(),
                document.costUsd(),
                document.credits(),
                document.reflectedFindingIds(),
                document.excludedFindingIds(),
                document.status(),
                generatedAt);
        report.recordStructuredContent(document.structuredContent());
        notificationAutomation.enqueueCompletedReport(report);
        events.publishEvent(new ReportCompleted(reportId));
        return report.getId();
    }

    private CollectionRun lockedRun(Long runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "보고서를 만들 수집 실행이 없습니다. runId=" + runId));
    }

    /** 신규 기사 없는 실행을 생략하면 reportId는 null이며 owner는 false다. */
    public record Reservation(Long reportId, boolean owner) {
    }
}
