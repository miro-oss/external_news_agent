package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightSnapshotAssemblerTest {
    NewsReportRepository reports = mock(NewsReportRepository.class);
    FindingRepository findings = mock(FindingRepository.class);
    TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    ReportInsightSnapshotAssembler assembler = new ReportInsightSnapshotAssembler(reports, findings, relevance, new ObjectMapper());
    @BeforeEach void setup() { when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0)); }

    @Test void snapshotUsesOnlyReportSelectionAndResolvableGroundedStoredClaims() {
        var report = daily(List.of(50L));
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var finding = finding(50, "원문 문장", List.of(
                new FindingKeyPoint("전망 원문", List.of(0), "grounded", null, "FORECAST", null),
                new FindingKeyPoint("의견 원문", List.of(0), "grounded", null, "OPINION", "발언자"),
                new FindingKeyPoint("약한 근거", List.of(0), "weak"),
                new FindingKeyPoint("없는 문장", List.of(2), "grounded"),
                new FindingKeyPoint("잘못된 귀속", List.of(0), "grounded", null, "FACT", "발언자")));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding));
        var snapshot = assembler.assemble(10L);
        assertEquals(List.of("50:0", "50:1"), snapshot.findings().getFirst().claims().stream().map(claim -> claim.id()).toList());
        assertEquals("FORECAST", snapshot.findings().getFirst().claims().getFirst().claimType());
        assertEquals("발언자", snapshot.findings().getFirst().claims().getLast().attributedTo());
        assertEquals(0, snapshot.findings().getFirst().sentences().getFirst().index());
        verify(findings, never()).findLatestByArticleIds(anyCollection());
    }
    @Test void changingOriginalEvidenceInvalidatesFingerprintEvenWhenClaimTextIsUnchanged() {
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(daily(List.of(50L))));
        var points = List.of(new FindingKeyPoint("원래 주장", List.of(0), "grounded"));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding(50, "첫 원문", points)), List.of(finding(50, "다른 원문", points)));
        assertNotEquals(assembler.assemble(10L).inputHash(), assembler.assemble(10L).inputHash());
    }
    @Test void frozenRunCoverageDoesNotAppendNewRunFindings() {
        var report = NewsReport.builder().id(10L).run(CollectionRun.builder().id(20L).build()).reportScope(ReportScope.RUN)
                .title("실행 리포트").coverageRecorded(true).reflectedFindingIds(List.of(50L)).reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var points = List.of(new FindingKeyPoint("주장", List.of(0), "grounded"));
        when(findings.findForReportByRunId(20L)).thenReturn(List.of(finding(50, "원문", points), finding(60, "새 기사 원문", points)));
        assertEquals(List.of(50L), assembler.assemble(10L).findings().stream().map(finding -> finding.id()).toList());
    }
    @Test void legacyRunExcludesFindingsAnalyzedAfterReportWasGenerated() {
        var report = NewsReport.builder().id(10L).run(CollectionRun.builder().id(20L).build()).reportScope(ReportScope.RUN)
                .title("실행 리포트").generatedAt(LocalDateTime.of(2026, 9, 30, 10, 0)).reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var points = List.of(new FindingKeyPoint("주장", List.of(0), "grounded"));
        var early = finding(50, "원문", points);
        var late = finding(60, "새 기사", points);
        late.replaceAnalysis(Finding.builder().keyPoints(points).sections(List.of(new FindingSection(0, "새 기사")))
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.LOW)).relevance(Relevance.IMPORTANT)
                .analysisSource(AnalysisSource.LLM).analyzedAt(LocalDateTime.of(2026, 9, 30, 11, 0)).build());
        when(findings.findForReportByRunId(20L)).thenReturn(List.of(early, late));
        assertEquals(List.of(50L), assembler.assemble(10L).findings().stream().map(finding -> finding.id()).toList());
    }

    @Test void runSnapshotKeepsCapturedReportOrderAndSensitivityUpdatesDoNotChangeHash() {
        var report = NewsReport.builder().id(10L).run(CollectionRun.builder().id(20L).build()).reportScope(ReportScope.RUN)
                .title("실행 리포트").coverageRecorded(true).reflectedFindingIds(List.of(60L, 50L)).reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var points = List.of(new FindingKeyPoint("주장", List.of(0), "grounded"));
        var first = finding(60, "원문", points);
        var second = finding(50, "둘째 원문", points);
        when(findings.findForReportByRunId(20L)).thenReturn(List.of(first, second));
        var original = assembler.assemble(10L);
        second.replaceAnalysis(Finding.builder().keyPoints(points).sections(second.getSections()).analysisSource(AnalysisSource.LLM)
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).relevance(Relevance.IMPORTANT)
                .analyzedAt(second.getAnalyzedAt()).build());
        var updated = assembler.assemble(10L);
        assertEquals(List.of(60L, 50L), updated.findings().stream().map(finding -> finding.id()).toList());
        assertEquals(original.inputHash(), updated.inputHash());
    }
    @Test void rejectedVisibilityAndMissingGroundedEvidenceFailBeforeAnyGeneration() {
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(daily(List.of(50L))));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding(50, "원문", List.of(new FindingKeyPoint("약한 주장", List.of(0), "weak")))));
        assertThrows(GeneralException.class, () -> assembler.assemble(10L));
        when(relevance.filterFindings(anyList())).thenReturn(List.of());
        assertThrows(GeneralException.class, () -> assembler.assemble(10L));
    }
    @Test void hiddenOrPendingReportReturnsSpecifiedNotFoundAndMoreThanFiftyDoesNotTruncate() {
        assertThrows(ReportException.class, () -> assembler.assemble(10L));
        var ids = IntStream.range(1, 52).mapToObj(Long::valueOf).toList();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(daily(ids)));
        when(findings.findForReportByIdIn(ids)).thenReturn(ids.stream().map(id -> finding(id, "원문",
                List.of(new FindingKeyPoint("주장", List.of(0), "grounded")))).toList());
        var exception = assertThrows(GeneralException.class, () -> assembler.assemble(10L));
        assertEquals("리포트 관점 인사이트는 검증된 근거 50개까지 지원합니다.", exception.getMessage());
    }
    private NewsReport daily(List<Long> ids) { return NewsReport.builder().id(10L).title("일일 리포트")
            .reportScope(ReportScope.DAILY).reflectedFindingIds(ids).reportStatus(ReportStatus.GENERATED).build(); }
    private Finding finding(long id, String sentence, List<FindingKeyPoint> points) {
        var article = Article.builder().id(id + 100).title("기사").canonicalUrl("https://example.com/" + id)
                .topic(Topic.builder().id(1L).name("주제").build()).body(sentence).fetchStatus(FetchStatus.FULLTEXT).build();
        return Finding.builder().id(id).article(article).analysisSource(AnalysisSource.LLM).keyPoints(points)
                .sections(List.of(new FindingSection(0, sentence))).sensitivity(FindingSensitivity.legacy(SensitivityLevel.LOW))
                .relevance(Relevance.IMPORTANT).analyzedAt(LocalDateTime.of(2026, 9, 30, 9, 0)).build();
    }
}
