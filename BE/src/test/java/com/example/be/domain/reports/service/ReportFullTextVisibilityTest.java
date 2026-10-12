package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportFullTextVisibilityTest {
    private final FindingRepository repository = mock(FindingRepository.class);

    @Test
    void companySelectionStaysFrozenForRunReadsWithoutRewritingLegacyCoverage() {
        Finding suppressed = finding(1, FetchStatus.FULLTEXT, "제외된 원문");
        Finding retained = finding(2, FetchStatus.FULLTEXT, "보존된 원문");
        when(repository.findForReportByRunId(42L)).thenReturn(List.of(suppressed, retained));
        var legacy = NewsReport.builder().run(CollectionRun.builder().id(42L).build())
                .coverageRecorded(true).reflectedFindingIds(List.of(2L)).build();
        assertEquals(2, ReportFindings.loadVisible(legacy, repository).findings().size());
        var selected = NewsReport.builder().run(CollectionRun.builder().id(42L).build())
                .companySelectionRecorded(true).companySuppressedFindingIds(List.of(1L))
                .coverageRecorded(true).reflectedFindingIds(List.of(2L)).build();
        var visible = ReportFindings.loadVisible(selected, repository);
        assertEquals(List.of(retained), visible.findings());
        assertFalse(visible.filtered());
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"GENERATED", "FALLBACK"})
    void completedCoverageCannotHideUncoveredFindingsOrForgetCompanyExclusions(ReportStatus status) {
        List<Finding> all = new ArrayList<>(LongStream.rangeClosed(1, 51)
                .mapToObj(id -> finding(id, FetchStatus.FULLTEXT, "검증 원문")).toList());
        Finding stub = finding(52, FetchStatus.FULLTEXT, "STUB 원문", AnalysisSource.STUB);
        Finding unsupported = finding(53, FetchStatus.FULLTEXT, "근거 부족 원문");
        Finding suppressed = finding(54, FetchStatus.FULLTEXT, "회사 기준 제외 원문");
        all.addAll(List.of(stub, unsupported, suppressed, finding(55, FetchStatus.METADATA_ONLY, "원문 아님")));
        when(repository.findForReportByRunId(42L)).thenReturn(all);
        var report = NewsReport.builder().run(CollectionRun.builder().id(42L).build())
                .reportStatus(ReportStatus.PENDING).build();
        report.freezeCompanySelection(all.stream().map(Finding::getId).filter(id -> id != 54L).toList(),
                List.of(54L), null);
        List<Long> covered = LongStream.rangeClosed(1, 50).boxed().toList();
        report.complete("보고서", "저장된 본문", "model", null, null, null, null, null, null,
                covered, List.of(53L), status, LocalDateTime.now());

        var visible = ReportFindings.loadVisible(report, repository);

        assertEquals(LongStream.rangeClosed(1, 53).boxed().toList(), visible.findings().stream().map(Finding::getId).toList());
        assertTrue(visible.findings().contains(stub), "full-text STUB remains visible despite missing generator coverage");
        assertFalse(visible.findings().contains(suppressed));
        assertTrue(visible.filtered(), "metadata-only evidence still triggers safe reading projection");
        assertEquals(List.of(54L), report.getCompanySuppressedFindingIds());
        assertEquals(covered, report.getReflectedFindingIds(), "visibility must not rewrite generator coverage");
        assertEquals(List.of(53L), report.getExcludedFindingIds());
    }

    @Test
    void runKeepsOnlyAvailableFullTextEvenWhenMetadataHasBodyText() {
        var report = NewsReport.builder().run(CollectionRun.builder().id(42L).build()).build();
        Finding available = finding(1, FetchStatus.FULLTEXT, "원문 문장");
        List<Finding> all = List.of(available, finding(2, FetchStatus.METADATA_ONLY, "요약 복사"),
                finding(3, FetchStatus.FULLTEXT, " \n\t"), finding(4, FetchStatus.FULLTEXT, null));
        when(repository.findForReportByRunId(42L)).thenReturn(all);
        var visible = ReportFindings.loadVisible(report, repository);
        assertEquals(List.of(available), visible.findings());
        assertTrue(visible.filtered());
        assertEquals(4, all.size());
    }

    @Test
    void dailyPreservesSavedOrderAndDoesNotReplaceMissingSelectedEvidence() {
        var report = NewsReport.builder().reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(3L, 2L, 1L, 99L)).build();
        Finding first = finding(1, FetchStatus.FULLTEXT, "원문");
        Finding last = finding(3, FetchStatus.FULLTEXT, "원문");
        when(repository.findForReportByIdIn(report.getReflectedFindingIds())).thenReturn(
                List.of(first, finding(2, FetchStatus.METADATA_ONLY, null), last));
        var visible = ReportFindings.loadVisible(report, repository);
        assertEquals(List.of(last, first), visible.findings());
        assertTrue(visible.filtered());
        assertEquals(List.of(3L, 2L, 1L, 99L), report.getReflectedFindingIds());
    }

    @Test
    void hiddenFindingRemovesStoredSummaryAndUnattributedMarkdownWithoutChangingSnapshot() {
        var stored = new ReportContent(List.of("숨겨야 할 요약"),
                List.of(new ReportContent.ImportantEvent("숨겨야 할 제목", "숨긴 사건", null, List.of(2L))),
                List.of(new ReportContent.WatchItem("숨긴 관찰", "숨긴 이유", List.of(2L))), List.of("숨긴 출처"));
        var report = NewsReport.builder().markdownBody("## 핵심 요약\n숨겨야 할 과거 본문")
                .structuredContent(stored).build();
        var view = ReportReadingContent.from(report,
                new ReportFindings.Visible(List.of(finding(1, FetchStatus.FULLTEXT, "원문 문장")), true));
        assertFalse(view.markdownBody().contains("숨"));
        assertFalse(view.structuredContent().toString().contains("숨"));
        assertTrue(view.markdownBody().contains("확인한 요약 1"));
        assertEquals(List.of(1L), view.structuredContent().importantEvents().getFirst().sourceFindingIds());
        assertEquals(stored, report.getStructuredContent());
        assertEquals("## 핵심 요약\n숨겨야 할 과거 본문", report.getMarkdownBody());
    }

    @Test
    void unchangedVisibilityKeepsSavedContentAndLegacyNullContract() {
        var content = new ReportContent(List.of("저장 요약"), List.of(), List.of(), List.of());
        var report = NewsReport.builder().markdownBody("저장 본문").structuredContent(content).build();
        var unchanged = ReportReadingContent.from(report, new ReportFindings.Visible(List.of(), false));
        assertEquals("저장 본문", unchanged.markdownBody());
        assertSame(content, unchanged.structuredContent());
        var legacy = NewsReport.builder().markdownBody("숨길 과거 본문").build();
        var filtered = ReportReadingContent.from(legacy, new ReportFindings.Visible(List.of(), true));
        assertNull(filtered.structuredContent());
        assertFalse(filtered.markdownBody().contains("숨길"));
        assertTrue(filtered.markdownBody().contains("기사가 없습니다"));
    }

    private Finding finding(long id, FetchStatus status, String body) {
        return finding(id, status, body, AnalysisSource.LLM);
    }

    private Finding finding(long id, FetchStatus status, String body, AnalysisSource source) {
        return Finding.builder().id(id).analysisSource(source)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.builder().score(java.math.BigDecimal.ZERO).build())
                .relevance(com.example.be.domain.analysis.entity.Relevance.IMPORTANT)
                .article(Article.builder().id(id).title("기사 " + id).fetchStatus(status).body(body)
                        .canonicalUrl("https://example.com/" + id).build())
                .summary("확인한 요약 " + id)
                .keyPoints(List.of(new FindingKeyPoint("확인한 주장 " + id, List.of(0), "grounded"))).build();
    }
}
