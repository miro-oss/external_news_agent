package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.sources.entity.Source;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ReportRecencyPolicyTest {
    private final CollectionRunItemRepository items = mock(CollectionRunItemRepository.class);
    private final ReportRecencyPolicy policy = new ReportRecencyPolicy(items,
            new CollectionPipelineProperties(), TopicRelevanceTestSupport.legacyPolicy());
    private final LocalDateTime started = LocalDateTime.of(2026, 11, 9, 9, 0);
    private final OffsetDateTime cutoff = started.atZone(ApiTimeZone.ZONE).toOffsetDateTime().minusDays(30);

    @Test
    void newSelectionUsesCapturedScopeAndOriginalRunTimeKeepingBoundaryAndUnknownDates() {
        capture(1L, 7L, "AI 기술");
        var old = finding(1L, 1L, 7L, cutoff.minusSeconds(1));
        var boundary = finding(2L, 1L, 7L, cutoff);
        var unknown = finding(3L, 1L, 7L, null);
        assertEquals(List.of(boundary, unknown), policy.filterForTopic(List.of(old, boundary, unknown), 7L));
        verify(items, times(1)).findExecutionItemsByRunId(1L);
    }

    @Test
    void currentTopicCannotTurnCapturedIndustryOrMissingScopeIntoTechnology() {
        capture(1L, 7L, "AI 산업");
        var industrial = finding(1L, 1L, 7L, cutoff.minusYears(5));
        var withoutSnapshot = finding(2L, 2L, 7L, cutoff.minusYears(5));
        assertEquals(List.of(industrial, withoutSnapshot),
                policy.filterForTopic(List.of(industrial, withoutSnapshot), 7L));
    }

    @Test
    void sharedArticleUsesRequestedTopicAndRetainsAnEligibleNontechnicalContext() {
        when(items.findExecutionItemsByRunId(1L)).thenReturn(List.of(item(7L, "AI 기술"), item(8L, "AI 산업")));
        var article = finding(1L, 1L, 7L, cutoff.minusYears(5));
        assertEquals(List.of(), policy.filterForTopic(List.of(article), 7L));
        assertEquals(List.of(article), policy.filterForTopic(List.of(article), 8L));
        assertEquals(List.of(article), policy.filter(List.of(article), Map.of(1L, Set.of(7L, 8L))));
    }

    private void capture(Long runId, Long topicId, String query) {
        when(items.findExecutionItemsByRunId(runId)).thenReturn(List.of(item(topicId, query)));
    }

    private CollectionRunItem item(Long topicId, String query) {
        return CollectionRunItem.builder().source(Source.builder().id(1L).build()).topicSnapshot(new CollectionTopicSnapshot(
                topicId, query, query, List.of(), List.of(), List.of(), 100, 1440)).build();
    }

    private Finding finding(Long id, Long runId, Long topicId, OffsetDateTime publishedAt) {
        return Finding.builder().id(id).run(CollectionRun.builder().id(runId).startedAt(started).build())
                .article(Article.builder().id(id).topic(Topic.builder().id(topicId).queryText("AI 기술").build())
                        .publishedAt(publishedAt).build()).build();
    }
}
