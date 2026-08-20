package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.agent.service.AgentReportOrchestrator;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
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
    private final NewsReportRepository reportRepository;
    private final AgentReportOrchestrator reportOrchestrator;
    private final ReportPersistenceService persistenceService;

    /** Agent HTTP 호출 동안 DB 잠금을 잡지 않고, 최종 저장 구간에서만 run을 잠근다. */
    public Long generate(Long runId) {
        Long existingId = reportRepository.findByRunId(runId)
                .map(report -> report.getId())
                .orElse(null);
        if (existingId != null) {
            return persistenceService.attachExisting(runId, existingId);
        }

        CollectionRun run = runRepository.findReportContextById(runId)
                .orElseThrow(() -> new IllegalStateException("보고서를 만들 수집 실행이 없습니다. runId=" + runId));
        List<Finding> findings = findingRepository.findForReportByRunId(runId);
        LocalDateTime generatedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        ReportDocument document = reportOrchestrator.generate(run, findings, generatedAt);
        return persistenceService.saveIfAbsent(runId, document, generatedAt);
    }
}
