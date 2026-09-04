package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.agent.investigation.IssueInvestigationJdbcRepository;
import com.example.be.domain.analysis.agent.investigation.InvestigationTrace;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportQueryServiceImplTest {

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final NewsIssueRepository newsIssueRepository = mock(NewsIssueRepository.class);
    private final DeliveryLogRepository deliveryLogRepository = mock(DeliveryLogRepository.class);
    private final IssueInvestigationJdbcRepository investigationRepository =
            mock(IssueInvestigationJdbcRepository.class);
    private final ReportQueryServiceImpl service = new ReportQueryServiceImpl(
            reportRepository, findingRepository, issueArticleRepository,
            newsIssueRepository, deliveryLogRepository,
            com.example.be.domain.analysis.service.SensitivityCalculator.defaults(),
            investigationRepository);

    @Test
    void latestReturnsNullWhenNoReportExists() {
        when(reportRepository.findFirstByReportStatusNotOrderByGeneratedAtDescIdDesc(ReportStatus.PENDING))
                .thenReturn(Optional.empty());

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
        when(findingRepository.countForReports(
                eq(List.of(42L)), any(java.math.BigDecimal.class))).thenReturn(List.of());

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
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(count.getCategory()).thenReturn("정책");
        when(count.getChangeType()).thenReturn(ChangeType.NEW);
        when(count.getFindingCount()).thenReturn(2L);
        when(count.getHighSensitivityCount()).thenReturn(2L);
        when(findingRepository.countStatsByRunId(
                eq(42L), eq(new java.math.BigDecimal("40")), eq(new java.math.BigDecimal("70"))))
                .thenReturn(List.of(count));

        ReportResDTO.Detail detail = service.getReport(17L, false);

        assertNull(detail.getFindings());
        assertEquals(2, detail.getSummaryStats().getFindingCount());
        assertEquals(2, detail.getSummaryStats().getNewCount());
        assertEquals(2, detail.getSummaryStats().getBySensitivityLevel().get("high"));
        verify(findingRepository, never()).findForReportByRunId(42L);
    }

    @Test
    void detailFindingsUseSamePriorityOrderAsGeneratedMarkdown() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .modelName("claude-sonnet-5")
                .promptVersion("report.ko.v1.4")
                .llmProvider("anthropic")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        Finding low = finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE);
        Finding high = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        IssueArticleRepository.CoverageMembership matchingMembership = membership(101L, 88L, 7L);
        IssueArticleRepository.CoverageMembership wrongTopicMembership = membership(102L, 99L, 8L);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .title("HBM4 양산 일정 이슈")
                .summary("양산 일정이 앞당겨졌다.")
                .lastSeenAt(OffsetDateTime.parse("2026-08-18T09:00:00+09:00"))
                .articleCount(3)
                .publisherCount(2)
                .independentContentCount(2)
                .topic(topic(7L))
                .entities(List.of("SK하이닉스", "HBM4"))
                .build();
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(low, high));
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(List.of(101L, 102L)))
                .thenReturn(List.of(matchingMembership, wrongTopicMembership));
        when(newsIssueRepository.findAllById(List.of(88L))).thenReturn(List.of(issue));
        when(investigationRepository.findTraces(42L)).thenReturn(java.util.Map.of(
                88L,
                new InvestigationTrace("NO_NEW_EVIDENCE", 1, 2, 0,
                        "B사 입장 확인", null)));

        ReportResDTO.Detail detail = service.getReport(17L, true);

        assertEquals(List.of(1L, 2L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
        assertEquals("report.ko.v1.4", detail.getPromptVersion());
        assertEquals("anthropic", detail.getLlmProvider());
        assertEquals(88L, detail.getFindings().getFirst().getIssueId());
        assertEquals("HBM4 양산 일정 이슈", detail.getFindings().getFirst().getIssue().getTitle());
        assertEquals(3, detail.getFindings().getFirst().getIssue().getArticleCount());
        assertEquals("NO_NEW_EVIDENCE",
                detail.getFindings().getFirst().getInvestigation().getStatus());
        assertEquals(2,
                detail.getFindings().getFirst().getInvestigation().getAddedArticleCount());
        assertNull(detail.getFindings().get(1).getIssueId());
        assertEquals("CHIP_MAKER", detail.getFindings().getFirst()
                .getPerspectiveTags().getFirst().getAudience());
        ReportResDTO.KeyPoint keyPoint = detail.getFindings().getFirst().getKeyPoints().getFirst();
        assertEquals("핵심", keyPoint.getText());
        assertEquals(List.of(0), keyPoint.getEvidence());
        assertEquals("grounded", keyPoint.getGroundedness());
        assertEquals("직접 확인", keyPoint.getGroundingReason());
        assertEquals("OPINION", keyPoint.getClaimType());
        assertEquals("분석가", keyPoint.getAttributedTo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailChunksIssueMembershipQueriesBelowOracleLimit() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        List<Finding> findings = LongStream.rangeClosed(1, 1_001)
                .mapToObj(id -> finding(id, SensitivityLevel.LOW, Relevance.REFERENCE))
                .toList();
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(findings);
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(anyCollection()))
                .thenReturn(List.of());

        service.getReport(17L, true);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(issueArticleRepository, times(2))
                .findCoverageMembershipsByArticleIds(ids.capture());
        assertEquals(List.of(900, 101), ids.getAllValues().stream().map(Collection::size).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyLoadsSavedFindingsAcrossRunsAndCountsTheSameWithoutDetails() {
        var date = java.time.LocalDate.of(2026, 9, 3);
        NewsReport daily = NewsReport.builder().id(70L)
                .reportScope(com.example.be.domain.reports.entity.ReportScope.DAILY).reportDate(date)
                .sourceRunIds(List.of(42L, 43L)).reflectedFindingIds(List.of(2L, 1L))
                .generatedAt(date.plusDays(1).atStartOfDay()).build();
        Finding first = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        Finding second = finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE);
        org.springframework.test.util.ReflectionTestUtils.setField(first, "run", CollectionRun.builder().id(42L).build());
        org.springframework.test.util.ReflectionTestUtils.setField(second, "run", CollectionRun.builder().id(43L).build());
        when(reportRepository.findByIdAndReportStatusNot(70L, ReportStatus.PENDING)).thenReturn(Optional.of(daily));
        when(findingRepository.findForReportByIdIn(List.of(2L, 1L))).thenReturn(List.of(first, second));
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(daily)));

        var detail = service.getReport(70L, true);
        assertNull(detail.getRunId());
        assertEquals(date, detail.getReportDate());
        assertEquals(List.of(2L, 1L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
        assertEquals(List.of(43L, 42L), detail.getFindings().stream().map(ReportResDTO.Finding::getRunId).toList());
        assertEquals(2, service.getReport(70L, false).getSummaryStats().getFindingCount());
        var count = mock(FindingRepository.DailyReportCount.class);
        when(count.getReportId()).thenReturn(70L);
        when(count.getFindingCount()).thenReturn(2L);
        when(count.getHighSensitivityCount()).thenReturn(1L);
        when(findingRepository.countForDailyReports(eq(List.of(70L)), any())).thenReturn(List.of(count));
        var summary = service.getReports(null, null, 0, 20).getContent().getFirst();
        assertEquals(2, summary.getFindingCount());
        assertEquals(1, summary.getHighSensitivityCount());
        verify(findingRepository, never()).findForReportByRunId(any());
        verify(investigationRepository).findTraces(List.of(43L, 42L));
        verify(investigationRepository, never()).findTraces(any(Long.class));
        verify(findingRepository, times(2)).findForReportByIdIn(anyCollection());
    }

    private Finding finding(Long id, SensitivityLevel sensitivityLevel, Relevance relevance) {
        Article article = Article.builder()
                .id(id + 100)
                .topic(topic(7L))
                .title("기사 " + id)
                .canonicalUrl("https://example.com/" + id)
                .build();
        return Finding.builder()
                .run(CollectionRun.builder().id(42L).build())
                .id(id)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("요약 " + id)
                .keyPoints(List.of(
                        new FindingKeyPoint(
                                "핵심", List.of(0), "grounded", "직접 확인", "OPINION", "분석가"),
                        new FindingKeyPoint("비근거 주장", List.of(1), "ungrounded")))
                .sentiment(Sentiment.NEUTRAL)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(sensitivityLevel))
                .relevance(relevance)
                .category("정책")
                .perspectiveTags(List.of(new FindingPerspectiveTag(
                        Audience.CHIP_MAKER,
                        AudienceRelevance.HIGH,
                        "생산 계획에 직접 영향을 줍니다.",
                        List.of(0))))
                .build();
    }

    private IssueArticleRepository.CoverageMembership membership(Long articleId,
                                                                  Long issueId,
                                                                  Long topicId) {
        IssueArticleRepository.CoverageMembership value =
                mock(IssueArticleRepository.CoverageMembership.class);
        when(value.getArticleId()).thenReturn(articleId);
        when(value.getIssueId()).thenReturn(issueId);
        when(value.getTopicId()).thenReturn(topicId);
        return value;
    }

    private Topic topic(Long id) {
        return Topic.builder().id(id).name("HBM").build();
    }
}
