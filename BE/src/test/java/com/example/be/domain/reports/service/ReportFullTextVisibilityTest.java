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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportFullTextVisibilityTest {
    private final FindingRepository repository = mock(FindingRepository.class);

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
        return Finding.builder().id(id).analysisSource(AnalysisSource.LLM)
                .article(Article.builder().id(id).title("기사 " + id).fetchStatus(status).body(body)
                        .canonicalUrl("https://example.com/" + id).build())
                .summary("확인한 요약 " + id)
                .keyPoints(List.of(new FindingKeyPoint("확인한 주장 " + id, List.of(0), "grounded"))).build();
    }
}
