package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ReportMaintenanceStoreTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackStore reviews = mock(FeedbackStore.class);
    private final ReportEventFeedbackProjection feedback = new ReportEventFeedbackProjection(reviews,
            mock(ReportEventSnapshotFactory.class), mock(DailyReportFeedbackExclusions.class));
    private final ReportMaintenanceStore store = new ReportMaintenanceStore(reports, runs, findings, relevance,
            feedback, new ObjectMapper());
    private final LocalDateTime generatedAt = LocalDateTime.of(2026, 11, 23, 9, 0);
    private final CollectionRun run = CollectionRun.builder().id(42L).status(RunStatus.SUCCESS).build();
    private final ReportContent content = new ReportContent(List.of("A", "B", "token"),
            List.of(event("A", 1), event("B", 2), event("token", 3), event("Claude", 4)), List.of(), List.of("saved note"));
    private final NewsReport report = NewsReport.builder().id(2310L).run(run).title("Original title")
            .reportStatus(ReportStatus.FALLBACK).generatedAt(generatedAt).modelName("safe-fallback-report-v1")
            .markdownBody("saved markdown").structuredContent(content).reflectedFindingIds(List.of(1L, 2L, 3L, 4L))
            .companySelectionRecorded(true).companySuppressedFindingIds(List.of(99L)).build();

    @BeforeEach void setup() {
        when(reports.findByIdAndReportStatusNot(2310L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(reports.findRunIdById(2310L)).thenReturn(Optional.of(42L));
        when(runs.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reports.findByIdForUpdate(2310L)).thenReturn(Optional.of(report));
        when(findings.findForReportByRunId(42L)).thenReturn(List.of(finding(1), finding(2), finding(3), finding(4), finding(99)));
        when(relevance.filterFindings(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test void manualSwapPreservesGenerationEvidenceAndIsIdempotent() {
        var before = store.read(2310L).snapshot();
        var after = store.promote(2310L, before.fingerprint(), "Claude", 2);

        assertEquals(List.of("A", "B", "Claude", "token"), after.eventTitles());
        assertEquals(2310L, after.reportId());
        assertEquals(generatedAt, report.getGeneratedAt());
        assertEquals("Original title", report.getTitle());
        assertEquals("safe-fallback-report-v1", report.getModelName());
        assertEquals(ReportStatus.FALLBACK, report.getReportStatus());
        assertEquals(List.of(1L, 2L, 3L, 4L), report.getReflectedFindingIds());
        assertEquals(List.of(99L), report.getCompanySuppressedFindingIds());
        assertTrue(report.getMarkdownBody().contains("### token"));
        assertEquals(after.fingerprint(), store.promote(2310L, after.fingerprint(), "Claude", 2).fingerprint());
        var locks = inOrder(runs, reports);
        locks.verify(runs).findByIdForUpdate(42L);
        locks.verify(reports).findByIdForUpdate(2310L);
    }

    @Test void generatedReplacementKeepsIdentityAndOriginalSavedTargetAndTokenDetail() {
        var before = store.read(2310L).snapshot();
        var generatedContent = new ReportContent(List.of("new"), List.of(event("new A", 1), event("new B", 2)),
                List.of(), List.of("fresh note"));
        var document = new ReportDocument("different title", "new markdown", "new-model", "report.v2", "openai",
                10L, 4L, null, null, ReportStatus.GENERATED, List.of(1L, 2L), List.of(3L, 4L), generatedContent);

        var after = store.replace(2310L, before.fingerprint(), document, "Claude", 2);

        assertEquals(List.of("A", "B", "Claude", "token"), after.eventTitles());
        assertEquals(generatedAt, after.generatedAt());
        assertEquals("Original title", report.getTitle());
        assertEquals("new-model", report.getModelName());
        assertEquals(ReportStatus.GENERATED, report.getReportStatus());
        assertEquals(List.of(1L, 2L, 4L, 3L), report.getReflectedFindingIds());
        assertTrue(report.getExcludedFindingIds().isEmpty());
        assertTrue(report.isCompanySelectionRecorded());
        assertEquals(List.of(99L), report.getCompanySuppressedFindingIds());
        assertFalse(report.isComparisonInputUsable());
        assertEquals(content.importantEvents().getLast(), report.getStructuredContent().importantEvents().get(2));
    }

    @Test void staleContentAndNewFeedbackBothBlockWrites() {
        String original = store.read(2310L).snapshot().fingerprint();
        report.reviseReadingContent("another editor", content);
        assertThrows(IllegalStateException.class, () -> store.promote(2310L, original, "Claude", 2));
        String current = store.read(2310L).snapshot().fingerprint();
        var review = new FeedbackModels.Feedback(1L, null, null, 2310L, null, FeedbackModels.Category.OTHER,
                "new opinion", false, "request", FeedbackModels.Status.PENDING, null, null, generatedAt, null, "event-key");
        when(reviews.eventFeedback(2310L)).thenReturn(List.of(review));
        assertThrows(IllegalStateException.class, () -> store.promote(2310L, current, "Claude", 2));
        assertEquals("another editor", report.getMarkdownBody());
        assertSame(content, report.getStructuredContent());
    }

    private static ReportContent.ImportantEvent event(String title, long id) {
        return new ReportContent.ImportantEvent(title, title + " summary", "", List.of(id));
    }

    private Finding finding(long id) {
        return Finding.builder().id(id).run(run).analysisSource(AnalysisSource.LLM)
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).relevance(Relevance.IMPORTANT)
                .keyPoints(List.of(new FindingKeyPoint("supported", List.of(0), "grounded")))
                .article(Article.builder().id(id).title("article " + id).body("body")
                        .fetchStatus(FetchStatus.FULLTEXT).build()).build();
    }
}
