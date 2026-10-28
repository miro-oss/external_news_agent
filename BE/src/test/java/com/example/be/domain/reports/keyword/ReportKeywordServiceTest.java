package com.example.be.domain.reports.keyword;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.DailyReportFeedbackExclusions;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportKeywordServiceTest {
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final DailyReportFeedbackExclusions inherited = mock(DailyReportFeedbackExclusions.class);
    private final ReportKeywordService service = new ReportKeywordService(articles, relevance, inherited);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-29T09:00:00+09:00");
    private static final List<ReportKeywordSnapshot.Definition> WORDS = List.of(
            new ReportKeywordSnapshot.Definition("hbm", "HBM"), new ReportKeywordSnapshot.Definition("패키징", "패키징"),
            new ReportKeywordSnapshot.Definition("gpu", "GPU"));

    @Test void denominatorRetainsPreRankingCandidatesAndDeduplicatesRepeatedIssuesAndKeywords() {
        var snapshot = snapshot(List.of(candidate(1, 11, "hbm", "패키징"), candidate(2, 11, "hbm"),
                candidate(3, 22, "패키징"), candidate(4, 33, "hbm", "패키징")));
        fullText(1, 2, 3, 4);
        var result = service.project(report(snapshot), view(List.of(1L), Set.of()));
        assertEquals(ReportKeywordDTO.Status.AVAILABLE, result.status());
        assertEquals(3, result.denominatorIssueCount());
        assertEquals(List.of("패키징", "HBM", "GPU"), result.items().stream().map(ReportKeywordDTO.Item::keyword).toList());
        assertEquals(3, result.items().getFirst().issueCount());
        assertEquals(100.0, result.items().getFirst().sharePercent());
        assertEquals(2, result.items().get(1).issueCount());
        assertEquals(66.7, result.items().get(1).sharePercent());
        assertEquals(List.of(new ReportKeywordDTO.RelatedKeyword("HBM", 2)), result.items().getFirst().relatedKeywords());
        assertEquals(0, result.items().getLast().issueCount());
        assertEquals(0.0, result.items().getLast().sharePercent());
        assertTrue(result.items().getLast().relatedKeywords().isEmpty());
        assertEquals(4, snapshot.candidates().size());
    }

    @Test void missingSnapshotIsNotAZeroAndKnownEmptyScopeKeepsNullShares() {
        var missing = service.project(report(null), view(List.of(), Set.of()));
        assertEquals("NO_SNAPSHOT", missing.unavailableReason());
        assertNull(missing.denominatorIssueCount());
        assertNull(missing.ruleVersion());
        assertNull(missing.capturedAt());
        assertTrue(missing.items().isEmpty());
        var empty = service.project(report(snapshot(List.of())), view(List.of(), Set.of()));
        assertEquals(ReportKeywordDTO.Status.AVAILABLE, empty.status());
        assertEquals(0, empty.denominatorIssueCount());
        assertTrue(empty.items().stream().allMatch(item -> item.issueCount() == 0 && item.sharePercent() == null));
        verifyNoInteractions(articles, relevance);
    }

    @Test void noRegisteredKeywordsRemainsAvailableWithoutInventingWords() {
        fullText(1);
        var snapshot = new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, NOW, "기간", null, List.of(),
                List.of(candidate(1, 11)));
        var result = service.project(report(snapshot), view(List.of(1L), Set.of()));
        assertEquals(ReportKeywordDTO.Status.AVAILABLE, result.status());
        assertEquals(1, result.denominatorIssueCount());
        assertTrue(result.items().isEmpty());
    }

    @Test void fullTextAndFrozenRunTopicRejectionOnlyRemoveExistingContributions() {
        var snapshot = snapshot(List.of(candidate(1, 11, "hbm"), candidate(2, 22, "패키징"), candidate(3, 33, "gpu")));
        fullText(1, 3);
        when(relevance.excludedKeys(1L)).thenReturn(Set.of(new TopicRelevanceGate.Key(3L, 10L)));
        var result = service.project(report(snapshot), view(List.of(1L), Set.of()));
        assertEquals(1, result.denominatorIssueCount());
        assertEquals("HBM", result.items().getFirst().keyword());
        assertEquals(1, result.items().getFirst().issueCount());
        assertTrue(result.items().getFirst().relatedKeywords().isEmpty());
    }

    @Test void excludedEvidenceAndCompanySuppressionPropagateThroughFrozenIssueIdentity() {
        var snapshot = snapshot(List.of(candidate(1, 11, "hbm"), candidate(2, 11, "패키징"), candidate(3, 22, "gpu")));
        var coverage = new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, NOW, "기간", List.of(),
                List.of(new ReportCoverageSnapshot.Binding(99L, 11L, 10L)));
        var report = NewsReport.builder().id(99L).reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 10, 28))
                .reportKeywordSnapshot(snapshot).reportCoverageSnapshot(coverage)
                .companySuppressedFindingIds(List.of(3L)).build();
        fullText(1, 2, 3);
        var result = service.project(report, view(List.of(2L), Set.of(99L)));
        assertEquals(0, result.denominatorIssueCount());
        assertTrue(result.items().stream().allMatch(item -> item.issueCount() == 0 && item.relatedKeywords().isEmpty()));
        verifyNoInteractions(articles, relevance);
    }

    @Test void inheritedDailyExclusionsUseDeliveryModeAndApplyToUnselectedCandidates() {
        var snapshot = snapshot(List.of(candidate(1, 11, "hbm"), candidate(2, 22, "패키징")));
        var report = report(snapshot);
        fullText(1, 2);
        when(inherited.forKeywordCandidates(report, snapshot, true)).thenReturn(Set.of(2L));
        assertEquals(2, service.project(report, view(List.of(1L), Set.of())).denominatorIssueCount());
        var delivered = service.projectForDelivery(report, view(List.of(1L), Set.of()));
        assertEquals(1, delivered.denominatorIssueCount());
        assertEquals(0, delivered.items().stream().filter(item -> item.keyword().equals("패키징")).findFirst().orElseThrow().issueCount());
        verify(inherited).forKeywordCandidates(eq(report), eq(snapshot), eq(true));
    }

    @Test void topSixAndRelatedTopThreeUseStableKeyOrderAfterCounts() {
        var definitions = IntStream.range(0, 9).mapToObj(index -> new ReportKeywordSnapshot.Definition("k" + index, "키워드" + index)).toList();
        var keys = definitions.stream().map(ReportKeywordSnapshot.Definition::key).toArray(String[]::new);
        var snapshot = new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, NOW, "기간", null, definitions,
                List.of(candidate(1, 11, keys), candidate(2, 22, "k8")));
        fullText(1, 2);
        var result = service.project(report(snapshot), view(List.of(), Set.of()));
        assertEquals(3, result.omittedKeywordCount());
        assertEquals(List.of("키워드8", "키워드0", "키워드1", "키워드2", "키워드3", "키워드4"),
                result.items().stream().map(ReportKeywordDTO.Item::keyword).toList());
        assertEquals(List.of("키워드0", "키워드1", "키워드2"), result.items().getFirst().relatedKeywords().stream()
                .map(ReportKeywordDTO.RelatedKeyword::keyword).toList());
    }

    @Test void unknownVersionUnknownKeywordsConflictingIdentityAndUnavailableReasonsNeverLeakPartialCounts() {
        var unknownVersion = new ReportKeywordSnapshot("future", NOW, "기간", null, WORDS, List.of(candidate(1, 11, "hbm")));
        var unknownKeyword = snapshot(List.of(candidate(1, 11, "invented")));
        var conflicting = snapshot(List.of(candidate(1, 11, "hbm"), candidate(1, 22, "hbm")));
        var incomplete = new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, NOW, "기간", "INCOMPLETE_SOURCE", WORDS,
                List.of(candidate(1, 11, "hbm")));
        for (var snapshot : List.of(unknownVersion, unknownKeyword, conflicting, incomplete)) {
            var result = service.project(report(snapshot), view(List.of(1L), Set.of()));
            assertEquals(ReportKeywordDTO.Status.UNAVAILABLE, result.status());
            assertNull(result.denominatorIssueCount());
            assertTrue(result.items().isEmpty());
        }
        assertEquals("INCOMPLETE_SOURCE", service.project(report(incomplete), view(List.of(), Set.of())).unavailableReason());
        verifyNoInteractions(articles, relevance, inherited);
    }

    @Test void personalizationUnavailablePreservesSnapshotMetadataWithoutAStatisticalSubset() {
        var report = report(snapshot(List.of(candidate(1, 11, "hbm"))));
        var result = service.unavailable(report, "PERSONALIZATION_UNVERIFIED");
        assertEquals(NOW, result.capturedAt());
        assertEquals("PERSONALIZATION_UNVERIFIED", result.unavailableReason());
        assertNull(result.denominatorIssueCount());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(articles, relevance, inherited);
    }

    @Test void unboundRejectedEvidenceMakesTheWholeCountUnavailableInsteadOfGuessingItsIssue() {
        var report = report(snapshot(List.of(candidate(1, 11, "hbm"))));
        var result = service.project(report, view(List.of(1L), Set.of(999L)));
        assertEquals("INVALID_SNAPSHOT", result.unavailableReason());
        assertNull(result.denominatorIssueCount());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(articles, relevance);
    }

    private void fullText(long... ids) {
        var values = java.util.Arrays.stream(ids).mapToObj(id -> {
            var article = mock(Article.class); when(article.getId()).thenReturn(id); when(article.hasFullText()).thenReturn(true); return article;
        }).toList();
        when(articles.findAllById(any())).thenReturn(values);
    }
    private static NewsReport report(ReportKeywordSnapshot snapshot) {
        return NewsReport.builder().id(99L).reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 10, 28))
                .reportKeywordSnapshot(snapshot).reflectedFindingIds(List.of(1L)).build();
    }
    private static ReportKeywordSnapshot snapshot(List<ReportKeywordSnapshot.Candidate> candidates) {
        return new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, NOW, "2026-10-28 수집 기준", null, WORDS, candidates);
    }
    private static ReportKeywordSnapshot.Candidate candidate(long finding, long issue, String... keys) {
        return new ReportKeywordSnapshot.Candidate(finding, finding, 1L, 10L, issue, List.of(keys));
    }
    private static ReportEventFeedbackProjection.View view(List<Long> ids, Set<Long> rejected) {
        var findings = ids.stream().map(id -> Finding.builder().id(id).build()).toList();
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), !rejected.isEmpty(), rejected);
    }
}
