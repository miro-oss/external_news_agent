package com.example.be.domain.reports.coverage;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.*;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.*;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.converter.ReportCoverageSnapshotConverter;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.service.DailyReportSelector;
import com.example.be.domain.reports.service.TopicWeeklyReportSelector;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportCoverageCaptureTest {
    private final CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
    private final IssueArticleRepository memberships = mock(IssueArticleRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final TopicRelevanceStore relevanceStore = mock(TopicRelevanceStore.class);
    private final ReportCoverageCapture capture = new ReportCoverageCapture(observations, memberships, findings, relevance, relevanceStore);
    private final LocalDate monday = LocalDate.of(2026, 10, 19);
    private final LocalDateTime now = monday.plusWeeks(1).atTime(9, 0);
    private final Topic topic = Topic.builder().id(3L).name("반도체").build();
    private final Source platform = Source.builder().id(6L).name("수집 플랫폼").build();
    private final CollectionRun run = CollectionRun.builder().id(7L).startedAt(monday.atTime(9, 0)).build();
    private final NewsIssue issue = NewsIssue.builder().id(20L).topic(topic).articleCount(4).build();

    @BeforeEach void setup() {
        when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void capturesAllObservedMembersEvenWithoutOwnAnalysisAndCopiesMetadataBeforeLaterMerges() {
        Article representative = article(1, " Reuters ");
        Article unanalysed = article(2, null);
        Article hidden = Article.builder().id(4L).topic(topic).title("숨김").fetchStatus(FetchStatus.METADATA_ONLY).build();
        Finding finding = finding(101, representative, run);
        var repLink = link(representative, issue);
        var secondLink = link(unanalysed, issue);
        when(findings.findForReportByRunId(7L)).thenReturn(List.of(finding));
        when(observations.findReportCoverageByRunIds(List.of(7L))).thenReturn(List.of(
                observation(11, representative, run, topic), observation(12, representative, run, topic),
                observation(13, unanalysed, run, topic), observation(14, hidden, run, topic)));
        when(memberships.findByArticleIds(anyCollection())).thenReturn(List.of(repLink, secondLink, link(hidden, issue)));
        var report = NewsReport.builder().run(run).build();
        var snapshot = capture.captureRun(report, now);
        assertEquals(List.of(1L, 2L), snapshot.observations().stream().map(ReportCoverageSnapshot.Observation::articleId).distinct().toList());
        assertEquals(3, snapshot.observations().size(), "preserve individual observations; projection unions article IDs");
        assertEquals("site:example.com", snapshot.observations().getFirst().publisherKey());
        assertNull(snapshot.observations().getLast().publisherKey(), "do not use Source.name as publisher fallback");
        assertEquals(List.of(new ReportCoverageSnapshot.Binding(101L, 20L, 3L)), snapshot.bindings());
        secondLink.moveToIssue(NewsIssue.builder().id(99L).topic(topic).articleCount(9).build());
        unanalysed.applyUpdate("나중 제목", "", "later text", "hash", FetchStatus.FULLTEXT, run, now);
        assertEquals("기사 2", snapshot.observations().getLast().title());
        assertEquals(20L, snapshot.observations().getLast().issueId());
        assertEquals("+09:00", snapshot.capturedAt().getOffset().toString());
    }

    @Test void dailyKeepsPreTopNCandidateBindingsButRejectsLaterNegativeAndWrongTopicObservations() {
        Article first = article(1, "매체 A");
        Article second = article(2, "매체 B");
        Article rejected = article(4, "매체 C");
        Topic other = Topic.builder().id(8L).build();
        NewsIssue issueTwo = NewsIssue.builder().id(21L).topic(topic).articleCount(2).build();
        Finding f1 = finding(101, first, run), f2 = finding(102, second, run);
        var prepared = new DailyReportSelector.Prepared(List.of(f1, f2), 0, 0, Map.of(101L, issue, 102L, issueTwo), 1);
        when(observations.findReportCoverageByRunIds(List.of(7L))).thenReturn(List.of(
                observation(11, first, run, topic), observation(12, second, run, topic),
                observation(13, rejected, run, topic), observation(14, first, run, other)));
        when(memberships.findByArticleIds(anyCollection())).thenReturn(List.of(link(first, issue), link(second, issueTwo), link(rejected, issue)));
        when(relevanceStore.latestOnDate(eq(monday), anyCollection())).thenReturn(List.of(
                new TopicRelevanceStore.DailyAssessment(4L, 3L, 9L, monday.atTime(20, 0), TopicRelevanceStatus.IRRELEVANT)));
        var report = NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(monday).sourceRunIds(List.of(7L)).build();
        var snapshot = capture.captureDaily(report, prepared, now);
        assertEquals(Set.of(101L, 102L), snapshot.bindings().stream().map(ReportCoverageSnapshot.Binding::findingId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(1L, 2L), snapshot.observations().stream().map(ReportCoverageSnapshot.Observation::articleId).toList());
    }

    @Test void topicWeeklyReadsAllTopicObservationsIncludingRunOutsideCandidateSourceRunIds() {
        Article representative = article(1, "매체 A"), additional = article(2, "매체 B");
        CollectionRun anotherRun = CollectionRun.builder().id(99L).startedAt(monday.plusDays(5).atTime(9, 0)).build();
        Finding finding = finding(101, representative, run);
        var report = NewsReport.builder().reportScope(ReportScope.WEEKLY).topicId(3L).reportDate(monday)
                .reportEndDate(monday.plusDays(6)).sourceRunIds(List.of(7L)).build();
        var prepared = new TopicWeeklyReportSelector.Prepared(3L, "반도체", monday, List.of(finding), Map.of(101L, issue));
        when(observations.findTopicReportCoverage(3L, monday.atStartOfDay(), monday.plusWeeks(1).atStartOfDay()))
                .thenReturn(List.of(observation(11, representative, run, topic), observation(12, additional, anotherRun, topic)));
        when(memberships.findByArticleIds(anyCollection())).thenReturn(List.of(link(representative, issue), link(additional, issue)));
        var snapshot = capture.captureTopicWeek(report, List.of(prepared), now);
        assertEquals(List.of(7L, 99L), snapshot.observations().stream().map(ReportCoverageSnapshot.Observation::runId).toList());
        verify(observations, never()).findReportCoverageByRunIds(anyCollection());
        assertEquals(List.of(7L), report.getSourceRunIds(), "legacy sourceRunIds contract stays intact");
    }

    @Test void weeklyUnionsOnlySavedEvidenceAndLeavesLegacySourcesUnavailable() {
        var member = new ReportCoverageSnapshot.Observation(11L, 7L, 3L, 20L, 1L, "a", "A", "기사", "https://example.com/1", null, null);
        var binding = new ReportCoverageSnapshot.Binding(101L, 20L, 3L);
        var saved = new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, now.atOffset(java.time.ZoneOffset.ofHours(9)),
                "일일", List.of(member), List.of(binding));
        NewsReport first = NewsReport.builder().id(1L).reportCoverageSnapshot(saved).build();
        NewsReport second = NewsReport.builder().id(2L).reportCoverageSnapshot(saved).build();
        var source = new WeeklyReportInput.DailySource(1L, monday, "일일", "", null, List.of(101L));
        var weekly = NewsReport.builder().reportScope(ReportScope.WEEKLY).reportDate(monday).reportEndDate(monday.plusDays(6))
                .weeklyInput(new WeeklyReportInput(monday, monday.plusDays(6), List.of(source), List.of())).build();
        var merged = capture.mergeWeekly(weekly, List.of(first, second), now);
        assertEquals(List.of(member), merged.observations());
        assertEquals(List.of(binding), merged.bindings());
        assertNull(capture.mergeWeekly(weekly, List.of(first, NewsReport.builder().id(3L).build()), now));
        verifyNoInteractions(observations, memberships, findings, relevance, relevanceStore);
    }

    @Test void emptyEvidenceIsRecordedAndWriteOnceSnapshotRoundTripsSeparatelyFromAbsentHistory() {
        var report = NewsReport.builder().run(run).reportStatus(ReportStatus.PENDING).build();
        var empty = capture.captureRun(report, now);
        assertNotNull(empty);
        assertTrue(empty.observations().isEmpty());
        assertTrue(empty.bindings().isEmpty());
        report.freezeReportCoverageSnapshot(empty);
        var replacement = new ReportCoverageSnapshot("different", empty.capturedAt(), "later", List.of(), List.of());
        report.freezeReportCoverageSnapshot(replacement);
        assertSame(empty, report.getReportCoverageSnapshot());
        var converter = new ReportCoverageSnapshotConverter();
        assertEquals(empty, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(empty)));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute(" "));
        assertNull(converter.convertToDatabaseColumn(null));
        assertThrows(IllegalStateException.class, () -> NewsReport.builder().reportStatus(ReportStatus.GENERATED)
                .build().freezeReportCoverageSnapshot(empty));
    }

    @Test void weeklyPreservesHiddenBindingsAndCannotReviveCompanyExcludedIssueThroughAnotherDay() {
        var firstMember = new ReportCoverageSnapshot.Observation(11L, 7L, 3L, 20L, 1L, "a", "A", "기사 A", "https://a.example/1", null, null);
        var secondMember = new ReportCoverageSnapshot.Observation(12L, 8L, 3L, 20L, 2L, "b", "B", "기사 B", "https://b.example/2", null, null);
        var firstBinding = new ReportCoverageSnapshot.Binding(101L, 20L, 3L);
        var secondBinding = new ReportCoverageSnapshot.Binding(102L, 20L, 3L);
        var firstSnapshot = new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, now.atOffset(java.time.ZoneOffset.ofHours(9)),
                "첫날", List.of(firstMember), List.of(firstBinding));
        var secondSnapshot = new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, firstSnapshot.capturedAt(),
                "다음날", List.of(secondMember), List.of(secondBinding));
        var first = NewsReport.builder().id(1L).reportCoverageSnapshot(firstSnapshot).build();
        var second = NewsReport.builder().id(2L).reportCoverageSnapshot(secondSnapshot).build();
        var source = new WeeklyReportInput.DailySource(2L, monday.plusDays(1), "다음날", "", null, List.of(102L));
        var weekly = NewsReport.builder().reportScope(ReportScope.WEEKLY).reportDate(monday).reportEndDate(monday.plusDays(6))
                .weeklyInput(new WeeklyReportInput(monday, monday.plusDays(6), List.of(source), List.of())).build();

        var omittedByRanking = capture.mergeWeekly(weekly, List.of(first, second), now);
        assertEquals(List.of(firstBinding, secondBinding), omittedByRanking.bindings(), "keep mappings for later inherited feedback");
        assertEquals(2, omittedByRanking.observations().size(), "ordinary top-N omission is not an exclusion");
        var suppressedFirst = NewsReport.builder().id(1L).reportCoverageSnapshot(firstSnapshot)
                .companySuppressedFindingIds(List.of(101L)).build();
        var excluded = capture.mergeWeekly(weekly, List.of(suppressedFirst, second), now);
        assertEquals(List.of(firstBinding, secondBinding), excluded.bindings());
        assertTrue(excluded.observations().isEmpty(), "same frozen issue/topic remains excluded across source days");
        assertEquals(List.of(firstMember), firstSnapshot.observations(), "source evidence remains immutable");
        assertEquals(List.of(secondMember), secondSnapshot.observations());
        verifyNoInteractions(observations, memberships, findings, relevance, relevanceStore);
    }

    @Test void mergeBetweenPreparationAndReservationCannotAttachOldEvidenceToANewIssue() {
        Article article = article(1, "매체 A");
        Finding finding = finding(101, article, run);
        var prepared = new DailyReportSelector.Prepared(List.of(finding), 0, 0, Map.of(101L, issue), 1);
        var replacement = NewsIssue.builder().id(99L).topic(topic).articleCount(2).build();
        when(observations.findReportCoverageByRunIds(List.of(7L))).thenReturn(List.of(observation(11, article, run, topic)));
        when(memberships.findByArticleIds(anyCollection())).thenReturn(List.of(link(article, replacement)));
        var report = NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(monday).sourceRunIds(List.of(7L)).build();
        var snapshot = capture.captureDaily(report, prepared, now);
        assertTrue(snapshot.bindings().isEmpty(), "missing frozen binding means unavailable, not zero");
        assertEquals(99L, snapshot.observations().getFirst().issueId());
    }

    @Test void publisherNormalizationRejectsPlatformsWithoutInventingAliases() {
        assertEquals("Reuters", ReportCoverageCapture.publisher("  Ｒｅｕｔｅｒｓ  "));
        assertEquals("매체 이름", ReportCoverageCapture.publisher("매체  이름"));
        for (String value : List.of("Google News", "구글 뉴스", "NAVER", "unknown", "매체 미확인", " ")) {
            assertNull(ReportCoverageCapture.publisher(value), value);
        }
    }

    private Article article(long id, String publisher) {
        return Article.builder().id(id).topic(topic).source(platform).title("기사 " + id).canonicalUrl("https://example.com/" + id)
                .sourceName(publisher).fetchStatus(FetchStatus.FULLTEXT).body("확보한 기사 본문 " + id).build();
    }
    private Finding finding(long id, Article article, CollectionRun observedRun) {
        return Finding.builder().id(id).article(article).run(observedRun).build();
    }
    private IssueArticle link(Article article, NewsIssue newsIssue) {
        return IssueArticle.builder().id(article.getId()).article(article).issue(newsIssue).build();
    }
    private CollectionRunArticle observation(long id, Article article, CollectionRun observedRun, Topic observedTopic) {
        return CollectionRunArticle.builder().id(id).run(observedRun).article(article).topic(observedTopic).source(platform)
                .changeType(ChangeType.UNCHANGED).observedAt(observedRun.getStartedAt()).build();
    }
}
