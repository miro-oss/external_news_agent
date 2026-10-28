package com.example.be.domain.companyinterest;

import com.example.be.domain.analysis.agent.service.FeedbackAgentGateway;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.companyinterest.CompanyInterestModels.ActiveCriterion;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.service.WeeklyReportGenerator;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanyInterestSelectionTest {
    private final CompanyInterestStore store = mock(CompanyInterestStore.class);
    private final FeedbackAgentGateway agent = mock(FeedbackAgentGateway.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final CompanyInterestSelection selection = new CompanyInterestSelection(store, agent, json, relevance, findings);
    private final ActiveCriterion criterion = new ActiveCriterion(5, 1, "AI", "순수 수학 풀이 성능 소식은 제외한다.", 2);

    @BeforeEach void setup() {
        when(store.activeForTopics(anySet())).thenReturn(List.of(criterion));
    }

    @Test void suppressesOnlySupportedExclusionsAndSeparatesUncertainFromAppliedCounts() {
        when(agent.evaluate(isNull(), eq(1L), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(2L, "UNCERTAIN"), false));
        var result = selection.select(List.of(finding(1, "수학 연구 발표"), finding(2, "자료 부족")), 1L);
        assertEquals(List.of(2L), result.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(new CompanyInterestSelection.Application(criterion, 1, 1)), result.applications());
        verify(agent).evaluate(isNull(), eq(1L), argThat(request ->
                "COMPANY_REPORT".equals(request.path("scope").asString()) && "FREE".equals(request.path("plan").asString())));
        verify(store, never()).recordApplication(anyLong(), any(), anyInt(), anyInt(), any());
    }

    @Test void oversizedEvidenceIsRetainedWithoutClippingOrBlockingOtherArticles() {
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(), false));
        var oversized = finding(2, "긴 본문".repeat(3000));
        var result = selection.select(List.of(finding(1, "수학 연구"), oversized), 1L);
        assertEquals(List.of(oversized), result.findings());
        assertEquals(1, result.applications().getFirst().evaluatedCount());
        verify(agent).evaluate(any(), any(), argThat(request -> request.path("articles").size() == 1
                && request.path("articles").get(0).path("content").asString().equals("수학 연구")));
    }

    @Test void failedBatchKeepsItsArticlesAndRecordsOnlySuccessfulDefiniteDecisions() {
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> {
            JsonNode request = call.getArgument(2);
            if (request.path("articles").get(0).path("id").asLong() == 11) throw new IllegalStateException("Unavailable");
            return response(request, Map.of(), false);
        });
        var result = selection.select(IntStream.rangeClosed(1, 11).mapToObj(id -> finding(id, "수학 연구")).toList(), 1L);
        assertEquals(List.of(11L), result.findings().stream().map(Finding::getId).toList());
        assertEquals(new CompanyInterestSelection.Application(criterion, 10, 10), result.applications().getFirst());
        verify(agent, times(2)).evaluate(any(), any(), any());
    }

    @Test void mockedAndInventedEvidenceNeverSuppressOrClaimAnApplication() {
        var candidate = finding(1, "기사 원문");
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(), true));
        var mocked = selection.select(List.of(candidate), 1L);
        assertEquals(List.of(candidate), mocked.findings()); assertTrue(mocked.applications().isEmpty());
        doReturn(json.valueToTree(Map.of("meta", Map.of("mock", false, "truncated", false),
                "decisions", List.of(Map.of("articleId", 1, "status", "SUPPRESS", "policyIds", List.of(5),
                        "evidence", List.of(Map.of("articleId", 1, "quote", "원문에 없는 문장")), "reason", "제외"))))).when(agent).evaluate(any(), any(), any());
        var invalid = selection.select(List.of(candidate), 1L);
        assertEquals(List.of(candidate), invalid.findings()); assertTrue(invalid.applications().isEmpty());
    }

    @Test void aCriterionForOneTopicDoesNotSuppressAnotherAcceptedTopic() {
        Finding candidate = finding(1, "기업 활용과 수학 연구");
        when(relevance.relevantTopicIdsByFinding(anyList())).thenReturn(Map.of(1L, Set.of(1L, 2L)));
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(), false));
        var global = selection.select(List.of(candidate), null);
        assertEquals(List.of(candidate), global.findings());
        assertEquals(0, global.applications().getFirst().suppressedCount());
        assertTrue(selection.select(List.of(candidate), 1L).findings().isEmpty());
    }

    @Test void noActiveCriteriaNeverCallsTheAgentOrCreatesApplicationRecords() {
        when(store.activeForTopics(anySet())).thenReturn(List.of());
        Finding candidate = finding(1, "수학 연구");
        var result = selection.select(List.of(candidate), 1L);
        assertEquals(List.of(candidate), result.findings()); assertTrue(result.applications().isEmpty());
        verifyNoInteractions(agent);
    }

    @Test void uncertainOnlyEvaluationLeavesApplicationPending() {
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(1L, "UNCERTAIN"), false));
        Finding candidate = finding(1, "판단하기 어려운 기사 원문");
        var result = selection.select(List.of(candidate), 1L);
        assertEquals(List.of(candidate), result.findings());
        assertTrue(result.applications().isEmpty());
    }

    @Test void weeklyPreservesAnotherAcceptedTopicEvenWhenItsSourceSnapshotUsesOneIssueIdentity() {
        Finding candidate = finding(1, "기업 활용과 수학 연구");
        when(findings.findForReportByIdIn(List.of(1L))).thenReturn(List.of(candidate));
        when(relevance.relevantTopicIdsByFinding(anyList())).thenReturn(Map.of(1L, Set.of(1L, 2L)));
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(), false));
        LocalDate date = LocalDate.of(2026, 9, 21);
        var source = new WeeklyReportInput.DailySource(40L, date, "일일", "본문", null, List.of(1L),
                new ReportComparisonSnapshot(1, List.of(issue(1)), List.of()));
        var input = new WeeklyReportInput(date, date.plusDays(6), List.of(source), List.of());
        var result = selection.selectWeekly(input);
        assertEquals(List.of(1L), result.input().sourceFindingIds());
        assertEquals(0, result.applications().getFirst().suppressedCount());
    }

    @Test void globalWeeklyUsesFrozenAnalysisAndRemovesCompoundSectionsFromFallback() {
        Finding first = finding(1, "당시 수학 연구 원문");
        Finding second = finding(2, "당시 제품 출시 원문");
        when(findings.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(2L, "KEEP"), false));
        var content = new ReportContent(List.of("제거될 합성 요약"),
                List.of(new ReportContent.ImportantEvent("복합 이벤트", "제거될 합성 요약", "의미", List.of(1L, 2L))),
                List.of(new ReportContent.WatchItem("후속", "제거될 관찰", List.of(2L))), List.of());
        LocalDate date = LocalDate.of(2026, 9, 21);
        var snapshot = new ReportComparisonSnapshot(1, List.of(issue(1), issue(2)), List.of());
        var source = new WeeklyReportInput.DailySource(40L, date, "일일", "제거될 본문", content, List.of(1L, 2L), snapshot);
        var input = new WeeklyReportInput(date, date.plusDays(6), List.of(source), List.of());
        var result = selection.selectWeekly(input);
        assertTrue(result.input().sourceFindingIds().isEmpty());
        assertFalse(new WeeklyReportGenerator().generate(result.input()).markdownBody().contains("제거될"));
        assertEquals(List.of(1L, 2L), input.sourceFindingIds());
        assertEquals("제거될 본문", input.sources().getFirst().markdownBody());
        verify(agent).evaluate(any(), any(), argThat(request -> request.path("articles").get(0).path("title").asString().equals("당시 제목 1")
                && request.path("articles").get(0).path("content").asString().equals("당시 수학 연구 원문")));
    }

    @Test void recordKeepsFrozenVersionAndAggregatesDailyBatchesWithoutReadingCurrentCriteria() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);
        selection.record(50, List.of(new CompanyInterestSelection.Application(criterion, 3, 1),
                new CompanyInterestSelection.Application(criterion, 2, 2)), now);
        verify(store).recordApplication(50, criterion, 5, 3, now);
        verify(store, never()).activeForTopics(anySet());
    }

    @Test void weeklyReadsCriteriaForUnselectedKeywordTopicsWithoutAddingAnyAiEvaluationItem() {
        var otherCriterion = new ActiveCriterion(6, 2, "다른 주제", "다른 기준", 1);
        var input = weeklySingleFinding();
        when(store.activeForTopics(Set.of(1L, 2L))).thenReturn(List.of(otherCriterion));
        var result = selection.selectWeekly(input, Set.of(2L));
        assertEquals(Set.of(2L), result.criterionTopicIds());
        assertEquals(input, result.input());
        assertTrue(result.applications().isEmpty());
        verify(store).activeForTopics(Set.of(1L, 2L));
        verifyNoInteractions(agent);
    }

    @Test void weeklyCriterionProvenanceSurvivesUncertainAndFailedEvaluation() {
        var input = weeklySingleFinding();
        when(agent.evaluate(any(), any(), any())).thenAnswer(call -> response(call.getArgument(2), Map.of(1L, "UNCERTAIN"), false));
        var uncertain = selection.selectWeekly(input, Set.of(1L, 2L));
        assertEquals(Set.of(1L), uncertain.criterionTopicIds());
        assertTrue(uncertain.applications().isEmpty());
        assertEquals(input, uncertain.input());
        doThrow(new IllegalStateException("unavailable")).when(agent).evaluate(any(), any(), any());
        var failed = selection.selectWeekly(input, Set.of(1L, 2L));
        assertEquals(Set.of(1L), failed.criterionTopicIds());
        assertTrue(failed.applications().isEmpty());
        assertEquals(input, failed.input());
    }

    @Test void weeklyCriterionReadFailureMarksTheEntireRequestedTopicScopeUnverified() {
        var input = weeklySingleFinding();
        when(store.activeForTopics(Set.of(1L, 2L))).thenThrow(new IllegalStateException("unavailable"));
        var result = selection.selectWeekly(input, Set.of(2L));
        assertEquals(Set.of(1L, 2L), result.criterionTopicIds());
        assertTrue(result.applications().isEmpty());
        assertEquals(input, result.input());
        verifyNoInteractions(agent);
    }

    @Test void emptyWeeklyTextStillChecksAnExistingKeywordUniverseWithoutCallingAi() {
        var date = LocalDate.of(2026, 9, 21);
        var input = new WeeklyReportInput(date, date.plusDays(6), List.of(), List.of());
        var result = selection.selectWeekly(input, Set.of(1L));
        assertEquals(Set.of(1L), result.criterionTopicIds());
        assertEquals(input, result.input());
        verify(store).activeForTopics(Set.of(1L));
        verifyNoInteractions(agent, findings);
    }

    private WeeklyReportInput weeklySingleFinding() {
        when(findings.findForReportByIdIn(List.of(1L))).thenReturn(List.of(finding(1, "저장된 기사 원문")));
        var date = LocalDate.of(2026, 9, 21);
        var source = new WeeklyReportInput.DailySource(40L, date, "일일", "본문", null, List.of(1L),
                new ReportComparisonSnapshot(1, List.of(issue(1)), List.of()));
        return new WeeklyReportInput(date, date.plusDays(6), List.of(source), List.of());
    }

    private Finding finding(long id, String content) {
        return Finding.builder().id(id).sections(List.of(new FindingSection(0, content)))
                .article(Article.builder().id(id).title("현재 제목 " + id).body("나중에 바뀐 기사 본문")
                        .topic(Topic.builder().id(1L).name("AI").build()).build()).build();
    }
    private ReportComparisonSnapshot.Issue issue(long id) {
        return new ReportComparisonSnapshot.Issue(id, "hash", "prompt", "2026-09-21",
                new ReportChanges.Side(id, 1, "당시 제목 " + id, "요약", List.of()));
    }
    private JsonNode response(JsonNode request, Map<Long, String> statuses, boolean mock) {
        List<Map<String, Object>> decisions = new ArrayList<>();
        request.path("articles").forEach(article -> {
            long id = article.path("id").asLong();
            String status = statuses.getOrDefault(id, "SUPPRESS");
            decisions.add(Map.of("articleId", id, "status", status, "policyIds", status.equals("SUPPRESS") ? List.of(5) : List.of(),
                    "reason", "원문 근거에 따른 판단", "evidence", status.equals("SUPPRESS")
                            ? List.of(Map.of("articleId", id, "quote", article.path("content").asString())) : List.of()));
        });
        return json.valueToTree(Map.of("decisions", decisions, "meta", Map.of("mock", mock, "truncated", false)));
    }
}
