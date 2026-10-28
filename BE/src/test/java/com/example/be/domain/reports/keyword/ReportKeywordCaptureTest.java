package com.example.be.domain.reports.keyword;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.companyinterest.CompanyInterestModels.ActiveCriterion;
import com.example.be.domain.companyinterest.CompanyInterestStore;
import com.example.be.domain.reports.converter.ReportKeywordSnapshotConverter;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.service.AgentReportOrchestrator;
import com.example.be.domain.reports.service.DailyReportSelector;
import com.example.be.domain.reports.service.TopicWeeklyReportSelector;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportKeywordCaptureTest {
    private final FindingRepository findings = mock(FindingRepository.class);
    private final AgentReportOrchestrator orchestrator = mock(AgentReportOrchestrator.class);
    private final CompanyInterestStore companyInterests = mock(CompanyInterestStore.class);
    private final ReportKeywordCapture capture = new ReportKeywordCapture(findings, orchestrator, companyInterests);
    private final LocalDate monday = LocalDate.of(2026, 10, 19);
    private final LocalDateTime now = monday.plusWeeks(1).atTime(9, 0);
    private final Topic topic = Topic.builder().id(3L).name("반도체").build();
    private final CollectionRun run = CollectionRun.builder().id(7L).startedAt(monday.atTime(9, 0)).build();

    @Test void dailyCapturesPreTopNCandidatesAndOnlySourceTextMatchesFrozenPositiveKeywords() {
        var first = finding(101, 1, "AI 반도체", "HBM 공급 전망", "추론 전용 키워드");
        var second = finding(102, 2, "RAIL 운송", "GPU2 새 모델", "AI HBM GPU");
        var report = report(List.of(first, second), List.of("ＡＩ", " 반도체 "), List.of("ai", "HBM", "GPU"), List.of());
        var prepared = prepared(List.of(first, second));
        var snapshot = capture.captureDaily(report, prepared, now);
        assertNull(snapshot.unavailableReason());
        assertEquals(2, snapshot.candidates().size(), "ranking limit one must not shrink the denominator");
        assertEquals(List.of("ai", "hbm", "반도체"), snapshot.candidates().getFirst().matchedKeywordKeys());
        assertTrue(snapshot.candidates().getLast().matchedKeywordKeys().isEmpty(), "AI summary is never keyword evidence");
        assertEquals(List.of("ai", "gpu", "hbm", "반도체"), snapshot.definitions().stream().map(ReportKeywordSnapshot.Definition::key).toList());
        assertEquals("AI", snapshot.definitions().getFirst().label());
        assertEquals("+09:00", snapshot.capturedAt().getOffset().toString());
        assertEquals(ReportKeywordSnapshot.RULE_VERSION, snapshot.ruleVersion());
        first.getArticle().applyUpdate("새 제목", "", "키워드 없음", "newhash", FetchStatus.FULLTEXT, run, now);
        assertEquals(List.of("ai", "hbm", "반도체"), snapshot.candidates().getFirst().matchedKeywordKeys());
        verifyNoInteractions(companyInterests);
    }

    @Test void excludesNegativeKeywordsAndDoesNotInterpretQueryExpressionsAsKeywords() {
        var finding = finding(101, 1, "AI GPU HBM", "body", "");
        var snapshot = capture.captureDaily(report(List.of(finding), List.of("AI"), List.of("GPU"), List.of("GPU")), prepared(List.of(finding)), now);
        assertEquals(List.of(new ReportKeywordSnapshot.Definition("ai", "AI")), snapshot.definitions());
        assertEquals(List.of("ai"), snapshot.candidates().getFirst().matchedKeywordKeys());
    }

    @Test void unsupportedAndStubInputsCannotBecomeValidIssueCandidates() {
        var valid = finding(101, 1, "AI", "body", "");
        var stub = Finding.builder().id(102L).article(valid.getArticle()).run(run).analysisSource(AnalysisSource.STUB).build();
        var unsupported = Finding.builder().id(103L).article(valid.getArticle()).run(run).analysisSource(AnalysisSource.LLM)
                .keyPoints(List.of()).build();
        var snapshot = capture.captureDaily(report(List.of(valid), List.of("AI"), List.of(), List.of()),
                prepared(List.of(valid, stub, unsupported)), now);
        assertEquals(List.of(101L), snapshot.candidates().stream().map(ReportKeywordSnapshot.Candidate::findingId).toList());
    }

    @Test void runUsesTheSameEligibleRepresentativesAsTheGenerator() {
        var first = finding(101, 1, "AI", "body", "");
        var second = finding(102, 2, "HBM", "body", "");
        var report = report(List.of(first, second), List.of("AI"), List.of("HBM"), List.of());
        when(findings.findForReportByRunId(7L)).thenReturn(List.of(first, second));
        when(orchestrator.companySelectionCandidates(anyList())).thenReturn(List.of(second));
        var snapshot = capture.captureRun(report, now);
        assertEquals(List.of(102L), snapshot.candidates().stream().map(ReportKeywordSnapshot.Candidate::findingId).toList());
        verify(orchestrator).companySelectionCandidates(List.of(first, second));
        verify(orchestrator, never()).generate(any(), anyList(), any());
    }

    @Test void topicWeekRetainsDifferentDaysOfOneIssueForLaterUnionAndDoesNotDuplicateSameCandidate() {
        var first = finding(101, 1, "AI", "body", "");
        var second = finding(102, 2, "HBM", "body", "");
        var report = report(List.of(first, second), List.of("AI"), List.of("HBM"), List.of());
        var one = new TopicWeeklyReportSelector.Prepared(3L, "반도체", monday, List.of(first), Map.of());
        var two = new TopicWeeklyReportSelector.Prepared(3L, "반도체", monday.plusDays(1), List.of(first, second), Map.of());
        var snapshot = capture.captureTopicWeek(report, List.of(one, two), now);
        assertEquals(2, snapshot.candidates().size());
        assertEquals(List.of(20L, 21L), snapshot.candidates().stream().map(ReportKeywordSnapshot.Candidate::issueId).toList());
    }

    @Test void missingOrAmbiguousFrozenContextIsUnavailableRatherThanAFalseZero() {
        var finding = finding(101, 1, "AI", "body", "");
        var complete = report(List.of(finding), List.of("AI"), List.of(), List.of());
        var missing = NewsReport.builder().reportCoverageSnapshot(complete.getReportCoverageSnapshot()).build();
        assertEquals("MISSING_COLLECTION_CONTEXT", capture.captureDaily(missing, prepared(List.of(finding)), now).unavailableReason());
        var wrongRun = NewsReport.builder().reportCoverageSnapshot(complete.getReportCoverageSnapshot())
                .collectionContexts(List.of(new ReportCollectionContext(99L, complete.getCollectionContexts().getFirst().topics()))).build();
        assertEquals("MISSING_COLLECTION_CONTEXT", capture.captureDaily(wrongRun, prepared(List.of(finding)), now).unavailableReason());
        var ambiguous = NewsReport.builder().reportCoverageSnapshot(complete.getReportCoverageSnapshot())
                .collectionContexts(List.of(complete.getCollectionContexts().getFirst(),
                        new ReportCollectionContext(7L, List.of(context(List.of("GPU"), List.of(), List.of()))))).build();
        assertEquals("MISSING_COLLECTION_CONTEXT", capture.captureDaily(ambiguous, prepared(List.of(finding)), now).unavailableReason());
        assertEquals("NO_SNAPSHOT", capture.captureDaily(complete, null, now).unavailableReason());
    }

    @Test void missingFrozenIssueBindingDoesNotInferCurrentIssueMembership() {
        var finding = finding(101, 1, "AI", "body", "");
        var report = report(List.of(), List.of("AI"), List.of(), List.of());
        assertEquals("INVALID_SNAPSHOT", capture.captureDaily(report, prepared(List.of(finding)), now).unavailableReason());
        verifyNoInteractions(findings, orchestrator, companyInterests);
    }

    @Test void verifiedEmptyUniverseAndNoConfiguredKeywordsRemainAvailable() {
        var empty = capture.captureDaily(report(List.of(), List.of("AI"), List.of(), List.of()), prepared(List.of()), now);
        assertNull(empty.unavailableReason());
        assertTrue(empty.candidates().isEmpty());
        assertEquals(1, empty.definitions().size());
        var finding = finding(101, 1, "AI", "body", "");
        var noKeywords = capture.captureDaily(report(List.of(finding), List.of(), List.of(), List.of()), prepared(List.of(finding)), now);
        assertNull(noKeywords.unavailableReason());
        assertTrue(noKeywords.definitions().isEmpty());
        assertEquals(1, noKeywords.candidates().size());
    }

    @Test void wholeWeekUnionsAllFrozenCandidatesWithoutRestrictingToDailyTopN() {
        var a = candidate(101, 1, 20, "ai");
        var b = candidate(102, 2, 21, "hbm");
        var c = candidate(103, 3, 20, "hbm");
        var first = source(1, List.of(a, b));
        var second = source(2, List.of(a, c));
        var snapshot = capture.mergeWeekly(week(List.of(101L)), List.of(first, second), now);
        assertNull(snapshot.unavailableReason());
        assertEquals(3, snapshot.candidates().size());
        assertEquals(2, snapshot.candidates().stream().map(ReportKeywordSnapshot.Candidate::issueId).distinct().count());
        verify(companyInterests).activeForTopics(Set.of(3L));
    }

    @Test void wholeWeekCannotUsePartialHistoricalSources() {
        var first = source(1, List.of(candidate(101, 1, 20, "ai")));
        var old = NewsReport.builder().id(2L).build();
        var result = capture.mergeWeekly(week(List.of(101L)), List.of(first, old), now);
        assertEquals("INCOMPLETE_SOURCE", result.unavailableReason());
        assertTrue(result.definitions().isEmpty());
        assertTrue(result.candidates().isEmpty());
    }

    @Test void companyExclusionOnOneDayRemovesSameFrozenIssueOnOtherDaysBeforeWeeklyAssessment() {
        var rejected = source(1, List.of(candidate(101, 1, 20, "ai")));
        rejected.freezeCompanySelection(List.of(), List.of(101L), null);
        var anotherDay = source(2, List.of(candidate(102, 2, 20, "hbm"), candidate(103, 3, 21, "ai")));
        var result = capture.mergeWeekly(week(List.of(103L)), List.of(rejected, anotherDay), now);
        assertEquals(List.of(103L), result.candidates().stream().map(ReportKeywordSnapshot.Candidate::findingId).toList());
        verifyNoInteractions(companyInterests);
    }

    @Test void activeCompanyCriterionHidesAnUnassessedPreTopNUniverseWithoutAnyNewEvaluation() {
        var source = source(1, List.of(candidate(101, 1, 20, "ai"), candidate(102, 2, 21, "hbm")));
        when(companyInterests.activeForTopics(Set.of(3L))).thenReturn(List.of(new ActiveCriterion(9, 3, "반도체", "관심 기준", 1)));
        var result = capture.mergeWeekly(week(List.of(101L)), List.of(source), now);
        assertEquals("COMPANY_SCOPE_UNVERIFIED", result.unavailableReason());
        assertTrue(result.candidates().isEmpty());
        verifyNoInteractions(orchestrator, findings);
        when(companyInterests.activeForTopics(Set.of(3L))).thenThrow(new IllegalStateException("unavailable"));
        assertEquals("COMPANY_SCOPE_UNVERIFIED", capture.mergeWeekly(week(List.of(101L)), List.of(source), now).unavailableReason());
    }

    @Test void snapshotRoundTripsAndCanOnlyBeFrozenOnceDuringNewReservation() {
        var report = report(List.of(), List.of("AI"), List.of(), List.of());
        var snapshot = capture.captureDaily(report, prepared(List.of()), now);
        var converter = new ReportKeywordSnapshotConverter();
        assertEquals(snapshot, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(snapshot)));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToDatabaseColumn(null));
        report.freezeReportKeywordSnapshot(snapshot);
        report.freezeReportKeywordSnapshot(source(1, List.of()).getReportKeywordSnapshot());
        assertSame(snapshot, report.getReportKeywordSnapshot());
        var complete = NewsReport.builder().reportStatus(ReportStatus.GENERATED).build();
        assertThrows(IllegalStateException.class, () -> complete.freezeReportKeywordSnapshot(snapshot));
        assertNull(complete.getReportKeywordSnapshot());
    }

    private Finding finding(long id, long articleId, String title, String body, String summary) {
        var article = Article.builder().id(articleId).topic(topic).title(title).body(body).fetchStatus(FetchStatus.FULLTEXT).build();
        return Finding.builder().id(id).article(article).run(run).analysisSource(AnalysisSource.LLM).summary(summary)
                .keyPoints(List.of(new FindingKeyPoint("근거 있는 분석", List.of(0), "grounded"))).build();
    }

    private NewsReport report(List<Finding> candidates, List<String> required, List<String> optional, List<String> excluded) {
        var bindings = new ArrayList<ReportCoverageSnapshot.Binding>();
        for (int i = 0; i < candidates.size(); i++) bindings.add(new ReportCoverageSnapshot.Binding(candidates.get(i).getId(), 20L + i, 3L));
        return NewsReport.builder().run(run).reportScope(ReportScope.DAILY).reportDate(monday).reportStatus(ReportStatus.PENDING)
                .collectionContexts(List.of(new ReportCollectionContext(7L, List.of(context(required, optional, excluded)))))
                .reportCoverageSnapshot(new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, now.atOffset(java.time.ZoneOffset.ofHours(9)),
                        "기간", List.of(), bindings)).build();
    }

    private CollectionTopicSnapshot context(List<String> required, List<String> optional, List<String> excluded) {
        return new CollectionTopicSnapshot(3L, "반도체", "HBM OR \"advanced packaging\"", required, optional, excluded, 100, 60);
    }

    private DailyReportSelector.Prepared prepared(List<Finding> candidates) {
        return new DailyReportSelector.Prepared(candidates, 0, 0, Map.of(), 1);
    }

    private ReportKeywordSnapshot.Candidate candidate(long finding, long article, long issue, String keyword) {
        return new ReportKeywordSnapshot.Candidate(finding, article, 7L, 3L, issue, List.of(keyword));
    }

    private NewsReport source(long id, List<ReportKeywordSnapshot.Candidate> candidates) {
        var snapshot = new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, now.atOffset(java.time.ZoneOffset.ofHours(9)), "기간", null,
                List.of(new ReportKeywordSnapshot.Definition("ai", "AI"), new ReportKeywordSnapshot.Definition("hbm", "HBM")), candidates);
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportDate(monday).reportStatus(ReportStatus.PENDING)
                .reportKeywordSnapshot(snapshot).reportCoverageSnapshot(new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION,
                        snapshot.capturedAt(), "기간", List.of(), candidates.stream().map(candidate ->
                        new ReportCoverageSnapshot.Binding(candidate.findingId(), candidate.issueId(), candidate.topicId())).toList())).build();
    }

    private NewsReport week(List<Long> selected) {
        return NewsReport.builder().reportScope(ReportScope.WEEKLY).reportDate(monday).reportEndDate(monday.plusDays(6))
                .weeklyInput(new WeeklyReportInput(monday, monday.plusDays(6), List.of(
                        new WeeklyReportInput.DailySource(1L, monday, "일일", "", null, selected)), List.of())).build();
    }
}
