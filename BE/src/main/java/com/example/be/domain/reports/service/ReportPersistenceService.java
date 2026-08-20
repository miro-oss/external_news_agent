package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportPersistenceService {

    private final CollectionRunRepository runRepository;
    private final NewsReportRepository reportRepository;

    @Transactional
    public Long attachExisting(Long runId, Long reportId) {
        CollectionRun run = lockedRun(runId);
        NewsReport existing = reportRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "연결할 보고서가 없습니다. runId=" + runId + " reportId=" + reportId));
        run.attachReport(existing.getId());
        return existing.getId();
    }

    @Transactional
    public Long saveIfAbsent(Long runId, ReportDocument document, LocalDateTime generatedAt) {
        CollectionRun run = lockedRun(runId);
        NewsReport existing = reportRepository.findByRunId(runId).orElse(null);
        if (existing != null) {
            run.attachReport(existing.getId());
            return existing.getId();
        }

        NewsReport report = reportRepository.save(NewsReport.builder()
                .run(run)
                .title(document.title())
                .markdownBody(document.markdownBody())
                .modelName(document.modelName())
                .promptVersion(document.promptVersion())
                .llmProvider(document.llmProvider())
                .inputTokens(document.inputTokens())
                .outputTokens(document.outputTokens())
                .costUsd(document.costUsd())
                .credits(document.credits())
                .reportStatus(document.status())
                .generatedAt(generatedAt)
                .build());
        run.attachReport(report.getId());
        return report.getId();
    }

    private CollectionRun lockedRun(Long runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "보고서를 만들 수집 실행이 없습니다. runId=" + runId));
    }
}
