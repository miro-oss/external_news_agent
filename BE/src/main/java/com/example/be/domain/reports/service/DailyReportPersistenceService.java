package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import lombok.extern.slf4j.Slf4j;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportPersistenceService {

    private final DailyReportJdbcRepository dailyRepository;
    private final NewsReportRepository reportRepository;
    private final CollectionRunRepository runRepository;
    private final ReportComparisonRepository comparisons;

    @Transactional
    public ReportPersistenceService.Reservation reserve(LocalDate date, List<Long> sourceRunIds,
                                                        List<Long> findingIds, LocalDateTime now) {
        return reserveWithSnapshot(date, sourceRunIds, findingIds, now, null);
    }

    @Transactional
    public ReportPersistenceService.Reservation reserveWithSnapshot(LocalDate date, List<Long> sourceRunIds,
            List<Long> findingIds, LocalDateTime now, ReportComparisonSnapshot snapshot) {
        dailyRepository.lockCreation();
        NewsReport existing = reportRepository.findByReportScopeAndReportDate(ReportScope.DAILY, date)
                .orElse(null);
        if (existing != null) {
            return new ReportPersistenceService.Reservation(existing.getId(), false);
        }
        NewsReport report = reportRepository.saveAndFlush(NewsReport.builder()
                .reportScope(ReportScope.DAILY).reportDate(date).sourceRunIds(sourceRunIds)
                .sourceReportCount(OracleInClause.batches(sourceRunIds).stream()
                        .mapToLong(ids -> reportRepository.countCompletedSourceReports(ids, now)).sum())
                .collectionContexts(runRepository.findAllById(sourceRunIds).stream()
                        .sorted(java.util.Comparator.comparing(run -> sourceRunIds.indexOf(run.getId())))
                        .map(ReportCollectionContext::from).toList())
                .reflectedFindingIds(findingIds).coverageRecorded(true)
                .title(date + " 일일 통합 뉴스 보고서")
                .markdownBody("보고서 생성이 진행 중입니다.").modelName("pending-report-v1")
                .reportStatus(ReportStatus.PENDING).generatedAt(now).build());
        if (snapshot != null) {
            try {
                comparisons.captureInput(report, snapshot, now);
            } catch (RuntimeException exception) {
                // Oracle statement failure does not abort the transaction; report generation remains available.
                log.warn("일일 보고서 비교 입력 저장 실패. reportId={}", report.getId(), exception);
            }
        }
        return new ReportPersistenceService.Reservation(report.getId(), true);
    }
}
