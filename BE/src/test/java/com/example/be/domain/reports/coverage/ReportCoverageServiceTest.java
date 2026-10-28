package com.example.be.domain.reports.coverage;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportCoverageServiceTest {
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final ReportCoverageService service = new ReportCoverageService(articles, relevance);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-29T09:00:00+09:00");

    @Test
    void countsUnionOfArticlesAcrossIssuesRunsAndTopicsInsteadOfAddingCounts() {
        var report = report(snapshot(List.of(observation(1, 1, 11, 100, "paper", 1L),
                        observation(2, 2, 11, 100, "paper", 1L), observation(3, 2, 22, 100, "paper", 1L),
                        observation(4, 2, 22, 200, null, 2L), observation(5, 2, 22, 300, "second", 3L)),
                List.of(binding(501, 11), binding(502, 22))));
        fullText(100, 200, 300);
        var result = service.project(report, view(List.of(501L, 502L), Set.of(), List.of(502L, 501L)));
        var event = result.forSources(List.of(501L, 502L, 501L));
        assertEquals(ReportCoverageDTO.Status.AVAILABLE, event.status());
        assertEquals(3, event.articleCount());
        assertEquals(2, event.publisherCount());
        assertEquals(1, event.unknownPublisherArticleCount());
        assertEquals(List.of(100L, 300L), event.representativeArticles().stream().map(ReportCoverageDTO.Article::articleId).toList());
        assertEquals(1, result.forFinding(501L).articleCount());
        assertEquals(3, result.forFinding(502L).articleCount());
        assertEquals(NOW, result.capturedAt());
        assertTrue(result.withoutFindings().findings().isEmpty());
        assertEquals(result.events(), result.withoutFindings().events());
    }

    @Test
    void absentSnapshotAndMissingBindingAreUnavailableButKnownEmptyScopeIsZero() {
        var legacy = service.project(report(null), view(List.of(501L), Set.of(), List.of(501L)));
        assertNull(legacy.ruleVersion());
        assertNull(legacy.forFinding(501L).articleCount());
        assertEquals(ReportCoverageDTO.Status.UNAVAILABLE, legacy.forFinding(501L).status());
        var zero = service.project(report(snapshot(List.of(), List.of(binding(501, 11)))),
                view(List.of(501L, 502L), Set.of(), List.of(501L)));
        assertEquals(0, zero.forFinding(501L).articleCount());
        assertEquals(ReportCoverageDTO.Status.AVAILABLE, zero.forFinding(501L).status());
        assertEquals(ReportCoverageDTO.Status.UNAVAILABLE, zero.forFinding(502L).status());
        assertNull(zero.forFinding(502L).publisherCount());
        verifyNoInteractions(articles, relevance);
    }

    @Test
    void hiddenEvidenceCannotReturnThroughAnotherFindingOfTheSameFrozenIssue() {
        var report = report(snapshot(List.of(observation(1, 1, 11, 100, "hidden", 1L),
                        observation(2, 1, 22, 200, "allowed", 2L)),
                List.of(binding(501, 11), binding(502, 11), binding(503, 22))));
        fullText(100, 200);
        var result = service.project(report, view(List.of(502L, 503L), Set.of(501L), List.of(502L, 503L)));
        assertEquals(1, result.events().getFirst().articleCount());
        assertEquals(List.of(200L), result.events().getFirst().representativeArticles().stream()
                .map(ReportCoverageDTO.Article::articleId).toList());
        assertEquals(0, result.forFinding(502L).articleCount());
    }

    @Test
    void currentFullTextAndFrozenTopicRejectionOnlyShrinkSnapshotAndNeverReadCurrentMembership() {
        var report = report(snapshot(List.of(observation(1, 1, 11, 100, "paper", 1L),
                        observation(2, 2, 11, 200, "second", 2L), observation(3, 2, 11, 300, "third", 3L)),
                List.of(binding(501, 11))));
        fullText(100, 300); // 200 lost its body; there is no replacement with a newly collected article.
        when(relevance.excludedKeys(2L)).thenReturn(Set.of(new TopicRelevanceGate.Key(300L, 10L)));
        var event = service.project(report, view(List.of(501L), Set.of(), List.of(501L))).events().getFirst();
        assertEquals(1, event.articleCount());
        assertEquals(100L, event.representativeArticles().getFirst().articleId());
        assertEquals("저장 제목 100", event.representativeArticles().getFirst().title());
    }

    @Test
    void companyExclusionAndUnsafeRepresentativeLinksRemainExcluded() {
        var unsafe = new ReportCoverageSnapshot.Observation(1L, 1L, 10L, 11L, 100L,
                "paper", "Paper", "저장 제목", "https://user:password@example.com/news", NOW, 1L);
        var report = report(snapshot(List.of(unsafe), List.of(binding(501, 11))));
        fullText(100);
        var projected = service.project(report, view(List.of(501L), Set.of(), List.of(501L)));
        assertNull(projected.forFinding(501L).representativeArticles().getFirst().canonicalUrl());
        var suppressed = NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 10, 28))
                .reportCoverageSnapshot(report.getReportCoverageSnapshot()).reflectedFindingIds(List.of(501L))
                .companySuppressedFindingIds(List.of(501L)).build();
        assertEquals(0, service.project(suppressed, view(List.of(501L), Set.of(), List.of(501L))).forFinding(501L).articleCount());
        assertNull(ReportCoverageService.safeUrl("javascript:alert(1)"));
        assertNull(ReportCoverageService.safeUrl("https://example.com/\nsecret"));
        assertEquals("https://example.com/news", ReportCoverageService.safeUrl("https://example.com/news"));
    }

    @Test
    void representativesPreferDifferentPublishersAndIndependentBodiesWithoutInventingUnknownNames() {
        var report = report(snapshot(List.of(observation(1, 1, 11, 100, null, 1L),
                observation(2, 1, 11, 200, "paper", 1L), observation(3, 1, 11, 300, "other", 2L)),
                List.of(binding(501, 11))));
        fullText(100, 200, 300);
        var reps = service.project(report, view(List.of(501L), Set.of(), List.of(501L)))
                .forFinding(501L).representativeArticles();
        assertEquals(List.of(100L, 300L), reps.stream().map(ReportCoverageDTO.Article::articleId).toList());
        assertNull(reps.getFirst().publisher());
    }

    private void fullText(long... ids) {
        List<Article> values = java.util.Arrays.stream(ids).mapToObj(id -> {
            Article article = mock(Article.class);
            when(article.getId()).thenReturn(id);
            when(article.hasFullText()).thenReturn(true);
            return article;
        }).toList();
        when(articles.findAllById(any())).thenReturn(values);
    }
    private static NewsReport report(ReportCoverageSnapshot snapshot) {
        return NewsReport.builder().id(99L).reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 10, 28))
                .reportCoverageSnapshot(snapshot).reflectedFindingIds(List.of(501L, 502L, 503L)).build();
    }
    private static ReportCoverageSnapshot snapshot(List<ReportCoverageSnapshot.Observation> observations,
                                                   List<ReportCoverageSnapshot.Binding> bindings) {
        return new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, NOW, "2026-10-28 수집 기준", observations, bindings);
    }
    private static ReportCoverageSnapshot.Binding binding(long findingId, long issueId) {
        return new ReportCoverageSnapshot.Binding(findingId, issueId, 10L);
    }
    private static ReportCoverageSnapshot.Observation observation(long id, long runId, long issueId,
            long articleId, String publisher, Long contentGroup) {
        return new ReportCoverageSnapshot.Observation(id, runId, 10L, issueId, articleId, publisher,
                publisher, "저장 제목 " + articleId, "https://example.com/" + articleId, NOW, contentGroup);
    }
    private static ReportEventFeedbackProjection.View view(List<Long> ids, Set<Long> excluded, List<Long> eventIds) {
        List<Finding> findings = ids.stream().map(id -> {
            var finding = mock(Finding.class); when(finding.getId()).thenReturn(id); return finding;
        }).toList();
        var content = new ReportContent(List.of("요약"), List.of(new ReportContent.ImportantEvent("이슈", "요약", "", eventIds)), List.of(), List.of());
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", content), !excluded.isEmpty(), excluded);
    }
}
