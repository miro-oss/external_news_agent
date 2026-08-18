package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportQueryServiceImplTest {

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final ReportQueryServiceImpl service = new ReportQueryServiceImpl(reportRepository, findingRepository);

    @Test
    void latestReturnsNullWhenNoReportExists() {
        when(reportRepository.findFirstByOrderByGeneratedAtDescIdDesc()).thenReturn(Optional.empty());

        assertNull(service.getLatest(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsZeroCountsWhenRunHasNoFindings() {
        com.example.be.domain.collection.entity.CollectionRun run =
                com.example.be.domain.collection.entity.CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder()
                .id(17L)
                .run(run)
                .title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0))
                .build();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report)));
        when(findingRepository.countForReports(List.of(42L))).thenReturn(List.of());

        var result = service.getReports(null, null, 0, 20);

        ReportResDTO.Summary summary = result.getContent().getFirst();
        assertEquals(0, summary.getFindingCount());
        assertEquals("NOT_SENT", summary.getDeliveryStatus());
    }

    @Test
    void rejectsInvertedPeriodWithSpecifiedMessage() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> service.getReports("2026-08-18", "2026-08-17", 0, 20));

        assertEquals("from은 to보다 이전이어야 합니다.", exception.getMessage());
    }
}
