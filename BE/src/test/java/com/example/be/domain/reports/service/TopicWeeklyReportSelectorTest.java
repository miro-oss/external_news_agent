package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.*;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.issues.entity.*;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicWeeklyReportSelectorTest {
    private final FindingRepository findings = mock(FindingRepository.class);
    private final IssueArticleRepository memberships = mock(IssueArticleRepository.class);
    private final TopicRelevanceStore store = mock(TopicRelevanceStore.class);
    private final TopicWeeklyReportSelector selector = new TopicWeeklyReportSelector(findings, memberships, new TopicRelevancePolicy(store));
    private final LocalDate date = LocalDate.of(2026, 9, 21);

    @Test
    void selectsTopicBeforeGlobalTopTenAndKeepsDetachedSupportedClaims() {
        List<Finding> candidates = new ArrayList<>();
        List<IssueArticle> links = new ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            Finding other = finding(index, 2L, 1);
            candidates.add(other); links.add(link(other, index, 2L, 100));
        }
        Finding target = finding(12, 1L, 1);
        candidates.add(target); links.add(link(target, 12, 1L, 1));
        inputs(candidates, links);
        var selected = selector.select(1L, "HBM", date);
        assertEquals(List.of(12L), selected.source().reflectedFindingIds());
        assertNull(selected.source().reportId());
        assertEquals("검증된 주장 12", selected.source().structuredContent().importantEvents().getFirst().summaryKo());
        ReflectionTestUtils.setField(target, "summary", "변경된 요약");
        ReflectionTestUtils.setField(target, "keyPoints", List.of());
        assertEquals("검증된 주장 12", selected.source().evidenceSnapshot().issues().getFirst().side().claims().getFirst().text());
        assertFalse(selected.source().structuredContent().toString().contains("검증되지 않은 요약"));
    }

    @Test
    void latestUnsupportedOrRejectedFindingNeverRevivesOlderIssueClaims() {
        Finding old = finding(1, 1L, 1);
        Finding latest = finding(2, 1L, 2);
        ReflectionTestUtils.setField(latest, "keyPoints", List.of(new FindingKeyPoint("거절", List.of(0), "ungrounded")));
        inputs(List.of(old, latest), List.of(link(old, 10, 1L, 10), link(latest, 10, 1L, 10)));
        assertTrue(selector.select(1L, "HBM", date).findings().isEmpty());
        ReflectionTestUtils.setField(latest, "keyPoints", List.of(new FindingKeyPoint("근거", List.of(0), "grounded")));
        when(store.findByRun(2L)).thenReturn(List.of(assessment(2L, 1L, 2L, TopicRelevanceStatus.IRRELEVANT),
                assessment(2L, 2L, 2L, TopicRelevanceStatus.RELEVANT)));
        assertTrue(selector.select(1L, "HBM", date).findings().isEmpty());
    }

    @Test
    void laterRejectionForRequestedTopicCannotBeBypassedByOtherAcceptedTopic() {
        Finding shared = finding(1, 2L, 1);
        inputs(List.of(shared), List.of(link(shared, 10, 1L, 10), link(shared, 20, 2L, 10)));
        when(store.findByRun(1L)).thenReturn(List.of(assessment(1L, 1L, 1L, TopicRelevanceStatus.RELEVANT),
                assessment(1L, 2L, 1L, TopicRelevanceStatus.RELEVANT)));
        assertEquals(1L, selector.select(1L, "HBM", date).source().evidenceSnapshot().issues().getFirst().side().topicId());
        when(store.latestOnDate(eq(date), anyList())).thenReturn(List.of(new TopicRelevanceStore.DailyAssessment(
                1L, 1L, 2L, date.atTime(2, 0), TopicRelevanceStatus.IRRELEVANT)));
        assertTrue(selector.select(1L, "HBM", date).findings().isEmpty());
    }

    @Test
    void excludesMissingFullTextStubAndUnresolvableSentencesAndLimitsWithinTopic() {
        List<Finding> candidates = new ArrayList<>();
        List<IssueArticle> links = new ArrayList<>();
        for (int index = 1; index <= 14; index++) {
            Finding finding = finding(index, 1L, 1);
            if (index == 1) ReflectionTestUtils.setField(finding.getArticle(), "fetchStatus", FetchStatus.METADATA_ONLY);
            if (index == 2) ReflectionTestUtils.setField(finding, "analysisSource", AnalysisSource.STUB);
            if (index == 3) ReflectionTestUtils.setField(finding, "sections", List.of());
            candidates.add(finding); links.add(link(finding, index, 1L, index));
        }
        inputs(candidates, links);
        var selected = selector.select(1L, "HBM", date);
        assertEquals(10, selected.findings().size());
        assertEquals(14L, selected.findings().getFirst().getId());
        assertFalse(selected.source().reflectedFindingIds().contains(1L));
        assertFalse(selected.source().reflectedFindingIds().contains(2L));
        assertFalse(selected.source().reflectedFindingIds().contains(3L));
    }

    @Test
    void otherTopicOnlyLaterAnalysisDoesNotSuppressAcceptedRequestedTopic() {
        Finding old = finding(1, 2L, 1);
        Finding latest = finding(2, 2L, 2);
        ReflectionTestUtils.setField(latest.getArticle(), "id", 1L);
        inputs(List.of(old, latest), List.of(link(old, 10, 1L, 10)));
        when(store.findByRun(1L)).thenReturn(List.of(assessment(1L, 1L, 1L, TopicRelevanceStatus.RELEVANT)));
        when(store.findByRun(2L)).thenReturn(List.of(assessment(2L, 2L, 1L, TopicRelevanceStatus.RELEVANT)));
        assertEquals(List.of(1L), selector.select(1L, "HBM", date).source().reflectedFindingIds());
    }

    @Test
    void keepsWeakForecastAndAttributedOpinionQualificationInFrozenEventsAndFallback() {
        Finding finding = finding(1, 1L, 1);
        ReflectionTestUtils.setField(finding, "keyPoints", List.of(
                new FindingKeyPoint("내년 공급이 증가한다.", List.of(0), "weak", "예비 추정", "FORECAST", null),
                new FindingKeyPoint("투자가 필요하다.", List.of(0), "grounded", null, "OPINION", "김 연구원")));
        inputs(List.of(finding), List.of(link(finding, 10, 1L, 10)));
        var selected = selector.select(1L, "HBM", date);
        var input = new com.example.be.domain.reports.entity.WeeklyReportInput(date, date.plusDays(6),
                List.of(selected.source()), date.plusDays(1).datesUntil(date.plusWeeks(1)).toList(), 1L, "HBM");
        String body = new WeeklyReportGenerator().generate(input).markdownBody();
        assertTrue(body.contains("근거 제한: 예비 추정"));
        assertTrue(body.contains("전망"));
        assertTrue(body.contains("김 연구원의 의견"));
        ReflectionTestUtils.setField(finding, "keyPoints", List.of(new FindingKeyPoint(
                "투자가 필요하다.", List.of(0), "grounded", null, "OPINION", null)));
        assertTrue(selector.select(1L, "HBM", date).findings().isEmpty());
    }

    private void inputs(List<Finding> candidates, List<IssueArticle> links) {
        when(findings.findDailyReportCandidates(date.atStartOfDay(), date.plusDays(1).atStartOfDay())).thenReturn(candidates);
        when(memberships.findByArticleIds(anyList())).thenReturn(links);
    }
    private TopicRelevanceStore.Assessment assessment(Long run, Long topic, Long article, TopicRelevanceStatus status) {
        return new TopicRelevanceStore.Assessment(run, topic, article, status, "", "", "", "", "");
    }
    private Finding finding(long id, Long topicId, int hour) {
        return Finding.builder().id(id).analysisSource(AnalysisSource.LLM)
                .run(CollectionRun.builder().id((long) hour).startedAt(date.atTime(hour, 0)).build())
                .article(Article.builder().id(id).topic(Topic.builder().id(topicId).build()).title("기사 " + id)
                        .fetchStatus(FetchStatus.FULLTEXT).body("검증된 본문").build())
                .summary("검증되지 않은 요약")
                .keyPoints(List.of(new FindingKeyPoint("검증된 주장 " + id, List.of(0), "grounded")))
                .sections(List.of(new FindingSection(0, "검증된 본문"))).build();
    }
    private IssueArticle link(Finding finding, long issueId, Long topicId, int score) {
        return IssueArticle.builder().article(finding.getArticle()).issue(NewsIssue.builder().id(issueId)
                .topic(Topic.builder().id(topicId).build()).articleCount(1).importanceScore(BigDecimal.valueOf(score)).build()).build();
    }
}
