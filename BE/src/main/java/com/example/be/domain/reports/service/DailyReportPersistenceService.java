package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyReportPersistenceService {

    private final DailyReportJdbcRepository dailyRepository;
    private final NewsReportRepository reportRepository;
    private final CollectionRunRepository runRepository;

    @Transactional
    public ReportPersistenceService.Reservation reserve(LocalDate date, List<Long> sourceRunIds,
                                                        List<Long> findingIds, LocalDateTime now) {
        dailyRepository.lockCreation();
        NewsReport existing = reportRepository.findByReportScopeAndReportDate(ReportScope.DAILY, date)
                .orElse(null);
        if (existing != null) {
            return new ReportPersistenceService.Reservation(existing.getId(), false);
        }
        NewsReport report = reportRepository.saveAndFlush(NewsReport.builder()
                .reportScope(ReportScope.DAILY).reportDate(date).sourceRunIds(sourceRunIds)
                .collectionContexts(runRepository.findAllById(sourceRunIds).stream()
                        .sorted(java.util.Comparator.comparing(run -> sourceRunIds.indexOf(run.getId())))
                        .map(ReportCollectionContext::from).toList())
                .reflectedFindingIds(findingIds).coverageRecorded(true)
                .title(date + " 일일 통합 뉴스 보고서")
                .markdownBody("보고서 생성이 진행 중입니다.").modelName("pending-report-v1")
                .reportStatus(ReportStatus.PENDING).generatedAt(now).build());
        return new ReportPersistenceService.Reservation(report.getId(), true);
    }
}
