package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ReportFindingOrderTest {
    @Test
    void technicalReportKeepsImportantReleaseAheadOfHighSensitivityReference() {
        Finding release = finding(1L, "AI 산업", Relevance.IMPORTANT, 0);
        Finding reference = finding(2L, "AI 모델", Relevance.REFERENCE, 100);
        assertEquals(List.of(release, reference), ReportFindingOrder.sortForGeneration(
                List.of(reference, release), run("AI 모델")));
        assertEquals(List.of(reference, release), ReportFindingOrder.sort(List.of(release, reference)),
                "saved report display must not switch with current topic settings");
    }

    @Test
    void mixedOrIndustrialReportKeepsExistingSensitivityOrder() {
        Finding technical = finding(1L, "AI 모델", Relevance.IMPORTANT, 0);
        Finding industrial = finding(2L, "AI 산업", Relevance.REFERENCE, 100);
        assertEquals(List.of(industrial, technical), ReportFindingOrder.sortForGeneration(
                List.of(technical, industrial), run("AI 산업")));
        assertEquals(List.of(industrial, technical), ReportFindingOrder.sortForGeneration(
                List.of(technical, industrial), run("AI 모델", "AI 산업")));
        assertEquals(List.of(industrial, technical), ReportFindingOrder.sortForGeneration(
                List.of(technical, industrial), CollectionRun.builder().build()));
        assertEquals(List.of(), ReportFindingOrder.sort(List.of()));
    }

    private CollectionRun run(String... queries) {
        var items = java.util.stream.IntStream.range(0, queries.length).mapToObj(index -> {
            var snapshot = new CollectionTopicSnapshot((long) index + 1, "AI 소식", queries[index],
                    List.of(), List.of(), List.of(), 100, 1440);
            return CollectionRunItem.builder().topicSnapshot(snapshot).build();
        }).toList();
        return CollectionRun.builder().items(items).build();
    }

    private Finding finding(Long id, String query, Relevance relevance, int score) {
        Finding finding = mock(Finding.class);
        when(finding.getId()).thenReturn(id);
        when(finding.getArticle()).thenReturn(Article.builder().topic(Topic.builder().queryText(query).build()).build());
        when(finding.getRelevance()).thenReturn(relevance);
        when(finding.getSensitivity()).thenReturn(FindingSensitivity.builder().score(java.math.BigDecimal.valueOf(score)).build());
        return finding;
    }
}
