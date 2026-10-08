package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentFeedbackExample;

import com.example.be.domain.feedback.service.FeedbackLearningService;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.feedback.model.FeedbackModels.Category;
import com.example.be.domain.feedback.model.FeedbackModels.EventContext;
import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.domain.feedback.model.FeedbackModels.Status;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
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
    FeedbackLearningService feedbackLearning = mock(FeedbackLearningService.class);
    FeedbackStore feedback = mock(FeedbackStore.class);
    ReportEventSnapshotFactory events = mock(ReportEventSnapshotFactory.class);
    ReportInsightSnapshotAssembler assembler = new ReportInsightSnapshotAssembler(reports, findings, relevance, new ObjectMapper(),
            new ReportEventFeedbackProjection(feedback, events), feedbackLearning);
    @BeforeEach void setup() { when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0)); }

    @Test void createAndReadKeepReportTimeLearningWhileLaterReportsReceiveNewReviews() {
        var scope = CollectionTopicSnapshot.capture(Topic.builder().id(1L).name("주제").build());
        var unrelated = CollectionTopicSnapshot.capture(Topic.builder().id(99L).name("다른 주제").build());
        var generatedAt = LocalDateTime.of(2026, 9, 30, 10, 0);
        var report = NewsReport.builder().id(10L).title("일일 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L)).reportStatus(ReportStatus.GENERATED).generatedAt(generatedAt)
                .collectionContexts(List.of(new ReportCollectionContext(42L, List.of(scope, unrelated)))).build();
        var example = new AgentFeedbackExample(12L, 1L, "SUMMARY_ERROR", "과거 사건", "과거 요약",
                "추정과 확정을 구분합니다.", List.of(new AgentFeedbackExample.Evidence(11L, "추정했다")));
        var laterExample = new AgentFeedbackExample(13L, 1L, "SUMMARY_ERROR", "다른 리포트 사건", "새로 검토한 요약",
                "발표와 시행을 구분합니다.", List.of(new AgentFeedbackExample.Evidence(14L, "발표했다")));
        var reviewed = new LinkedHashMap<LocalDateTime, AgentFeedbackExample>();
        reviewed.put(generatedAt.minusHours(1), example);
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding(50, "현재 근거",
                List.of(new FindingKeyPoint("현재 주장", List.of(0), "grounded")))));
        when(feedbackLearning.forSnapshots(eq(List.of(scope)), any(), any())).thenAnswer(call -> {
            LocalDateTime cutoff = call.getArgument(1);
            return reviewed.entrySet().stream().filter(entry -> !entry.getKey().isAfter(cutoff))
                    .map(Map.Entry::getValue).toList();
        });

        var created = assembler.assemble(10L);
        reviewed.put(generatedAt.plusHours(1), laterExample);
        var read = assembler.assembleForRead(10L);
        var recreated = assembler.assemble(10L);

        assertEquals(created.inputHash(), read.inputHash());
        assertEquals(created.inputHash(), recreated.inputHash());
        assertEquals(List.of(example), read.feedbackExamples());
        assertEquals(List.of(1L), read.findings().getFirst().topicIds());
        verify(feedbackLearning, times(3)).forSnapshots(eq(List.of(scope)), eq(generatedAt), any());

        var laterReport = NewsReport.builder().id(11L).title("다음 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L)).reportStatus(ReportStatus.GENERATED).generatedAt(generatedAt.plusDays(1))
                .collectionContexts(report.getCollectionContexts()).build();
        when(reports.findByIdAndReportStatusNot(11L, ReportStatus.PENDING)).thenReturn(Optional.of(laterReport));
        assertEquals(List.of(example, laterExample), assembler.assemble(11L).feedbackExamples());
    }

    @Test void missingReportGenerationTimeUsesDeterministicEmptyLearningWithoutLookup() {
        var scope = CollectionTopicSnapshot.capture(Topic.builder().id(1L).name("주제").build());
        var report = NewsReport.builder().id(10L).title("과거 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L)).reportStatus(ReportStatus.GENERATED)
                .collectionContexts(List.of(new ReportCollectionContext(42L, List.of(scope)))).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding(50, "현재 근거",
                List.of(new FindingKeyPoint("현재 주장", List.of(0), "grounded")))));

        var created = assembler.assemble(10L);
        var read = assembler.assembleForRead(10L);

        assertTrue(created.feedbackExamples().isEmpty());
        assertEquals(created.inputHash(), read.inputHash());
        verifyNoInteractions(feedbackLearning);
    }

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
        assertThrows(ReportException.class, () -> assembler.assembleForRead(10L));
        var ids = IntStream.range(1, 52).mapToObj(Long::valueOf).toList();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(daily(ids)));
        when(findings.findForReportByIdIn(ids)).thenReturn(ids.stream().map(id -> finding(id, "원문",
                List.of(new FindingKeyPoint("주장", List.of(0), "grounded")))).toList());
        var exception = assertThrows(GeneralException.class, () -> assembler.assemble(10L));
        assertEquals("리포트 관점 인사이트는 검증된 근거 50개까지 지원합니다.", exception.getMessage());
        assertEquals(ids, assembler.assembleForRead(10L).findings().stream().map(finding -> finding.id()).toList(),
                "a stored lookup uses the complete current input without a generation limit");
    }

    @Test void confirmedLocalErrorsRemoveExclusiveInputsAndInvalidateTheStoredInsightHash() {
        var rejected = event("오류 사건", 50L, 60L);
        var retained = event("남길 사건", 60L, 70L);
        var content = new ReportContent(List.of("저장된 요약"), List.of(rejected, retained), List.of(), List.of());
        var report = NewsReport.builder().id(10L).title("일일 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L, 60L, 70L)).structuredContent(content)
                .reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var points = List.of(new FindingKeyPoint("검증된 주장", List.of(0), "grounded"));
        when(findings.findForReportByIdIn(report.getReflectedFindingIds())).thenReturn(List.of(
                finding(50, "첫 원문", points), finding(60, "공유 원문", points), finding(70, "남길 원문", points)));
        when(events.capture(eq(report), any(ReportFindings.Visible.class))).thenReturn(List.of(
                item('a', 0, rejected), item('b', 1, retained)));
        when(feedback.eventFeedback(10L)).thenReturn(List.of(),
                List.of(review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null)));

        var original = assembler.assemble(10L);
        var updated = assembler.assemble(10L);

        assertEquals(List.of(50L, 60L, 70L), original.findings().stream().map(finding -> finding.id()).toList());
        assertEquals(List.of(60L, 70L), updated.findings().stream().map(finding -> finding.id()).toList());
        assertNotEquals(original.inputHash(), updated.inputHash(), "old saved insights must not match the refreshed report");
        assertSame(content, report.getStructuredContent());
        assertEquals(List.of(50L, 60L, 70L), report.getReflectedFindingIds());
    }

    @Test void feedbackKeysUseTheOriginalVisibleRunBeforeFrozenCoverageSelection() {
        var rejected = event("오류 사건", 50L);
        var report = NewsReport.builder().id(10L).run(CollectionRun.builder().id(20L).build())
                .reportScope(ReportScope.RUN).title("실행 리포트").coverageRecorded(true)
                .reflectedFindingIds(List.of(50L)).structuredContent(new ReportContent(List.of(),
                        List.of(rejected), List.of(), List.of())).reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        var points = List.of(new FindingKeyPoint("검증된 주장", List.of(0), "grounded"));
        when(findings.findForReportByRunId(20L)).thenReturn(List.of(
                finding(50, "반영 원문", points), finding(60, "이후 원문", points)));
        when(feedback.eventFeedback(10L)).thenReturn(List.of(
                review('a', Category.WRONG_CLUSTER, Status.COMPLETED, "CONFIRMED_ERROR", null)));
        when(events.capture(eq(report), any(ReportFindings.Visible.class))).thenAnswer(call -> {
            ReportFindings.Visible original = call.getArgument(1);
            assertEquals(Set.of(50L, 60L), new HashSet<>(original.findings().stream().map(Finding::getId).toList()));
            return List.of(item('a', 0, rejected));
        });

        var read = assembler.assembleForRead(10L);
        assertTrue(read.findings().isEmpty());
        assertEquals(64, read.inputHash().length());
        var exception = assertThrows(GeneralException.class, () -> assembler.assemble(10L));
        assertEquals("이 리포트는 인사이트에 사용할 검증된 근거가 없습니다.", exception.getMessage());
    }

    @Test void stalePendingUnconfirmedPreferencesAndRecipientErrorsKeepTheInsightInputHash() {
        var selected = event("저장된 사건", 50L);
        var report = NewsReport.builder().id(10L).title("일일 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L)).structuredContent(new ReportContent(List.of(),
                        List.of(selected), List.of(), List.of())).reportStatus(ReportStatus.GENERATED).build();
        when(reports.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findings.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding(50, "원문",
                List.of(new FindingKeyPoint("검증된 주장", List.of(0), "grounded")))));
        when(events.capture(eq(report), any(ReportFindings.Visible.class))).thenReturn(List.of(item('a', 0, selected)));
        var original = assembler.assemble(10L);
        for (var review : List.of(
                review('b', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.PENDING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.PROCESSING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.FAILED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "NOT_CONFIRMED", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "INSUFFICIENT_EVIDENCE", null),
                review('a', Category.PREFERENCE, Status.COMPLETED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", 99L))) {
            when(feedback.eventFeedback(10L)).thenReturn(List.of(review));
            assertEquals(original.inputHash(), assembler.assemble(10L).inputHash());
        }
    }

    private ReportContent.ImportantEvent event(String title, Long... ids) {
        return new ReportContent.ImportantEvent(title, title + " 요약", "판단 이유", List.of(ids));
    }
    private Item item(char key, int index, ReportContent.ImportantEvent event) {
        return new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                new EventContext(String.valueOf(key).repeat(64), index, event.title(), event.summaryKo(), event.significance(),
                        event.sourceFindingIds(), List.of(), List.of(), null));
    }
    private Feedback review(char key, Category category, Status status, String verdict, Long recipient) {
        return new Feedback(1L, null, recipient, 10L, null, category, "의견", false, "hash", status, verdict, "진단",
                LocalDateTime.of(2026, 10, 1, 12, 0), null, String.valueOf(key).repeat(64));
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
