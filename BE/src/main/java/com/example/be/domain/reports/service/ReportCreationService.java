package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportCreationService {

    private final CollectionRunRepository runRepository;
    private final FindingRepository findingRepository;
    private final AgentReportOrchestrator reportOrchestrator;
    private final ReportPersistenceService persistenceService;
    private final NewsReportRepository reportRepository;

    /** Agent HTTP 호출 동안 DB 잠금을 잡지 않는다. 신규 기사가 없으면 보고서 없이 null을 반환한다. */
    public Long generate(Long runId) {
        LocalDateTime generatedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        ReportPersistenceService.Reservation reservation = persistenceService.reserve(runId, generatedAt);
        if (!reservation.owner()) {
            return reservation.reportId();
        }

        CollectionRun run = runRepository.findReportContextById(runId)
                .orElseThrow(() -> new IllegalStateException("보고서를 만들 수집 실행이 없습니다. runId=" + runId));
        List<Finding> findings = findingRepository.findForReportByRunId(runId);
        ReportDocument document = reportOrchestrator.generate(run, findings, generatedAt);
        return persistenceService.complete(reservation.reportId(), document, generatedAt);
    }

    /** 저장된 finding으로 기존 실행 보고서를 교체하고, 보고서가 없으면 새로 만든다. */
    public Long recover(Long runId) {
        NewsReport existing = reportRepository.findByRunId(runId).orElse(null);
        if (existing == null) {
            return generate(runId);
        }
        LocalDateTime generatedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        CollectionRun run = runRepository.findReportContextById(runId)
                .orElseThrow(() -> new IllegalStateException("보고서를 만들 수집 실행이 없습니다. runId=" + runId));
        List<Finding> findings = findingRepository.findForReportByRunId(runId);
        ReportDocument document = reportOrchestrator.generate(run, findings, generatedAt);
        return persistenceService.replace(existing.getId(), document, generatedAt);
    }
}
