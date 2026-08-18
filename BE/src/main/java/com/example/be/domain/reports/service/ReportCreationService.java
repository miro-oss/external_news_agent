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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportCreationService {

    private final CollectionRunRepository runRepository;
    private final FindingRepository findingRepository;
    private final NewsReportRepository reportRepository;
    private final ReportGenerator reportGenerator;

    /** 같은 run을 잠근 상태에서 존재 확인·생성·역참조 연결을 한 트랜잭션으로 끝낸다. */
    @Transactional
    public Long generate(Long runId) {
        CollectionRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException("보고서를 만들 수집 실행이 없습니다. runId=" + runId));
        NewsReport existing = reportRepository.findByRunId(runId).orElse(null);
        if (existing != null) {
            run.attachReport(existing.getId());
            return existing.getId();
        }

        List<Finding> findings = findingRepository.findForReportByRunId(runId);
        LocalDateTime generatedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        ReportDocument document = reportGenerator.generate(findings, generatedAt);
        NewsReport report = reportRepository.save(NewsReport.builder()
                .run(run)
                .title(document.title())
                .markdownBody(document.markdownBody())
                .modelName(document.modelName())
                .generatedAt(generatedAt)
                .build());
        run.attachReport(report.getId());
        return report.getId();
    }
}
