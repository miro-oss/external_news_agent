package com.example.be.domain.collection.service.query;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRunCoverageServiceTest {

    private final CollectionRunArticleRepository observationRepository =
            mock(CollectionRunArticleRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final AnalysisSelectionProperties properties = new AnalysisSelectionProperties();
    private final CollectionRunCoverageService service = new CollectionRunCoverageService(
            observationRepository,
            issueArticleRepository,
            findingRepository,
            reportRepository,
            properties);

    @Test
    void separatesAssignmentAnalysisAndReportCoverage() {
        List<CollectionRunArticleRepository.CoverageObservation> observations = List.of(
                observation(1L, 7L), observation(2L, 7L), observation(3L, 7L));
        List<IssueArticleRepository.CoverageMembership> memberships = List.of(
                membership(1L, 101L, 7L),
                membership(2L, 101L, 7L),
                membership(3L, 102L, 7L));
        List<IssueArticleRepository.IssueRepresentative> representatives = List.of(
                representative(101L, 10L), representative(102L, 11L));
        when(observationRepository.findCoverageObservationsByRunId(42L)).thenReturn(observations);
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(Set.of(1L, 2L, 3L)))
                .thenReturn(memberships);
        when(issueArticleRepository.findRepresentativesByIssueIds(Set.of(101L, 102L)))
                .thenReturn(representatives);
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(
                finding(10L, "grounded"), finding(11L, "ungrounded")));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(
                NewsReport.builder().reportStatus(ReportStatus.GENERATED).build()));

        CollectionRunCoverage coverage = service.calculate(42L);

        assertEquals(3, coverage.observedArticleCount());
        assertEquals(3, coverage.issueAssignedArticleCount());
        assertEquals(BigDecimal.ONE, coverage.issueAssignmentRate());
        assertEquals(2, coverage.issueCount());
        assertEquals(2, coverage.analysisTargetIssueCount());
        assertEquals(2, coverage.llmAnalyzedIssueCount());
        assertEquals(BigDecimal.ONE, coverage.llmAnalysisRate());
        assertEquals(1, coverage.reportReflectedIssueCount());
        assertEquals(1, coverage.reportExcludedIssueCount());
        assertEquals(BigDecimal.ONE, coverage.reportCoverageRate());
    }

    @Test
    void returnsNullRatesWhenEveryDenominatorIsEmpty() {
        when(observationRepository.findCoverageObservationsByRunId(42L)).thenReturn(List.of());
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of());
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());

        CollectionRunCoverage coverage = service.calculate(42L);

        assertEquals(null, coverage.issueAssignmentRate());
        assertEquals(null, coverage.llmAnalysisRate());
        assertEquals(null, coverage.reportCoverageRate());
    }

    @Test
    void doesNotCountReportExclusionsBeforeReportCompletes() {
        CollectionRunArticleRepository.CoverageObservation observation = observation(1L, 7L);
        IssueArticleRepository.CoverageMembership membership = membership(1L, 101L, 7L);
        IssueArticleRepository.IssueRepresentative representative = representative(101L, 10L);
        Finding finding = finding(10L, "ungrounded");
        when(observationRepository.findCoverageObservationsByRunId(42L))
                .thenReturn(List.of(observation));
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(Set.of(1L)))
                .thenReturn(List.of(membership));
        when(issueArticleRepository.findRepresentativesByIssueIds(Set.of(101L)))
                .thenReturn(List.of(representative));
        when(findingRepository.findForReportByRunId(42L))
                .thenReturn(List.of(finding));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(
                NewsReport.builder().reportStatus(ReportStatus.PENDING).build()));

        CollectionRunCoverage coverage = service.calculate(42L);

        assertEquals(0, coverage.reportReflectedIssueCount());
        assertEquals(0, coverage.reportExcludedIssueCount());
        assertEquals(BigDecimal.ZERO, coverage.reportCoverageRate());
    }

    private CollectionRunArticleRepository.CoverageObservation observation(Long articleId, Long topicId) {
        CollectionRunArticleRepository.CoverageObservation value =
                mock(CollectionRunArticleRepository.CoverageObservation.class);
        when(value.getArticleId()).thenReturn(articleId);
        when(value.getTopicId()).thenReturn(topicId);
        return value;
    }

    private IssueArticleRepository.CoverageMembership membership(
            Long articleId, Long issueId, Long topicId) {
        IssueArticleRepository.CoverageMembership value =
                mock(IssueArticleRepository.CoverageMembership.class);
        when(value.getArticleId()).thenReturn(articleId);
        when(value.getIssueId()).thenReturn(issueId);
        when(value.getTopicId()).thenReturn(topicId);
        when(value.getRole()).thenReturn(IssueArticleRole.MEMBER);
        return value;
    }

    private IssueArticleRepository.IssueRepresentative representative(Long issueId, Long articleId) {
        IssueArticleRepository.IssueRepresentative value =
                mock(IssueArticleRepository.IssueRepresentative.class);
        when(value.getIssueId()).thenReturn(issueId);
        when(value.getArticleId()).thenReturn(articleId);
        return value;
    }

    private Finding finding(Long articleId, String groundedness) {
        return Finding.builder()
                .article(Article.builder().id(articleId).build())
                .analysisSource(AnalysisSource.LLM)
                .keyPoints(List.of(new FindingKeyPoint("주장", List.of(0), groundedness)))
                .analysisSections(List.of())
                .build();
    }
}
