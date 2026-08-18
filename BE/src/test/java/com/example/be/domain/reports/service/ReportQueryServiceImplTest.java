package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        assertEquals("from은 to보다 이후일 수 없습니다.", exception.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsEqualOffsetDateTimes() {
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = service.getReports(
                "2026-08-18T10:00:00+09:00", "2026-08-18T10:00:00+09:00", 0, 20);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void parsesSupportedDateFormatsAndInclusiveDateBoundaries() {
        assertEquals(LocalDateTime.of(2026, 8, 18, 10, 0),
                service.parseDateTime("2026-08-18T01:00:00Z", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 10, 0),
                service.parseDateTime("2026-08-18T10:00:00", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 0, 0),
                service.parseDateTime("2026-08-18", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 23, 59, 59, 999_999_999),
                service.parseDateTime("2026-08-18", true));
    }

    @Test
    void rejectsInvalidDateFormat() {
        assertThrows(GeneralException.class, () -> service.parseDateTime("18/08/2026", false));
    }

    @Test
    void detailWithoutFindingsUsesAggregateCountsWithoutLoadingClobs() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        FindingRepository.ReportStatsCount count = mock(FindingRepository.ReportStatsCount.class);
        when(reportRepository.findById(17L)).thenReturn(Optional.of(report));
        when(count.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(count.getCategory()).thenReturn("정책");
        when(count.getChangeType()).thenReturn(ChangeType.NEW);
        when(count.getFindingCount()).thenReturn(2L);
        when(findingRepository.countStatsByRunId(42L)).thenReturn(List.of(count));

        ReportResDTO.Detail detail = service.getReport(17L, false);

        assertNull(detail.getFindings());
        assertEquals(2, detail.getSummaryStats().getFindingCount());
        assertEquals(2, detail.getSummaryStats().getNewCount());
        assertEquals(2, detail.getSummaryStats().getByRiskLevel().get("high"));
        verify(findingRepository, never()).findForReportByRunId(42L);
    }

    @Test
    void detailFindingsUseSamePriorityOrderAsGeneratedMarkdown() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        Finding low = finding(2L, RiskLevel.LOW, Relevance.REFERENCE);
        Finding high = finding(1L, RiskLevel.HIGH, Relevance.IMPORTANT);
        when(reportRepository.findById(17L)).thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(low, high));

        ReportResDTO.Detail detail = service.getReport(17L, true);

        assertEquals(List.of(1L, 2L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
    }

    private Finding finding(Long id, RiskLevel riskLevel, Relevance relevance) {
        Article article = Article.builder()
                .id(id + 100)
                .title("기사 " + id)
                .canonicalUrl("https://example.com/" + id)
                .build();
        return Finding.builder()
                .id(id)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("요약 " + id)
                .keyPoints(List.of(new FindingKeyPoint("핵심", List.of(0), "grounded")))
                .sentiment(Sentiment.NEUTRAL)
                .riskLevel(riskLevel)
                .relevance(relevance)
                .category("정책")
                .build();
    }
}
