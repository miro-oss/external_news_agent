package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.companyinterest.CompanyInterestSelection;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.coverage.ReportCoverageCapture;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DailyReportPersistenceCoverageTest {
    @Test void persistsOnlyExplicitPreRankingCompanyExclusionsSeparatelyFromSelectedIds() {
        var reports = mock(NewsReportRepository.class);
        var interests = mock(CompanyInterestSelection.class);
        var service = new DailyReportPersistenceService(mock(DailyReportJdbcRepository.class), reports,
                mock(CollectionRunRepository.class), mock(ReportComparisonRepository.class), interests,
                mock(ReportCoverageCapture.class), mock(com.example.be.domain.reports.keyword.ReportKeywordCapture.class), mock(com.example.be.domain.reports.comparison.ReportComparisonCapture.class));
        var report = NewsReport.builder().id(9L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.PENDING).build();
        when(reports.findByIdForUpdate(9L)).thenReturn(Optional.of(report));
        var selected = Finding.builder().id(102L).build();
        var selection = new DailyReportSelector.Selection(List.of(selected), 0, 0, null, List.of(), List.of(101L));
        service.freezeSelection(9L, selection, LocalDateTime.of(2026, 10, 29, 9, 0));
        assertEquals(List.of(102L), report.getReflectedFindingIds());
        assertEquals(List.of(101L), report.getCompanySuppressedFindingIds());
    }
}
