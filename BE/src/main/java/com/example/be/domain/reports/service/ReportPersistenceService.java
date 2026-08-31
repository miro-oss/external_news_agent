package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportPersistenceService {

    private static final String PENDING_TITLE = "보고서 생성 중";
    private static final String PENDING_BODY = "보고서 생성이 진행 중입니다.";
    private static final String PENDING_MODEL = "pending-report-v1";

    private final CollectionRunRepository runRepository;
    private final NewsReportRepository reportRepository;

    @Transactional
    public Reservation reserve(Long runId, LocalDateTime generatedAt) {
        CollectionRun run = lockedRun(runId);
        NewsReport existing = reportRepository.findByRunId(runId).orElse(null);
        if (existing != null) {
            run.attachReport(existing.getId());
            return new Reservation(existing.getId(), false);
        }

        NewsReport report = reportRepository.save(NewsReport.builder()
                .run(run)
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
    public Long complete(Long reportId, ReportDocument document, LocalDateTime generatedAt) {
        NewsReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new IllegalStateException("완료할 보고서가 없습니다. reportId=" + reportId));
        report.complete(
                document.title(),
                document.markdownBody(),
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
        return report.getId();
    }

    private CollectionRun lockedRun(Long runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "보고서를 만들 수집 실행이 없습니다. runId=" + runId));
    }

    public record Reservation(Long reportId, boolean owner) {
    }
}
