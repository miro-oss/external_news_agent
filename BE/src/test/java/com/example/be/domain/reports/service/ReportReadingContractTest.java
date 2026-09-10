package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository.ReportArticleObservation;
import com.example.be.domain.reports.converter.ReportContentConverter;
import com.example.be.domain.reports.converter.ReportCollectionContextConverter;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReportReadingContractTest {
    @Test
    void countsUniqueArticlesAcrossSourcesAndRunsWithoutCallingFindingsArticles() {
        var stats = ReportArticleStatistics.count(List.of(
                observation(1, ChangeType.NEW), observation(1, ChangeType.UNCHANGED),
                observation(1, ChangeType.UPDATED), observation(2, ChangeType.UPDATED),
                observation(2, ChangeType.UNCHANGED), observation(3, ChangeType.UNCHANGED)));
        assertEquals(3, stats.getTotalCount());
        assertEquals(1, stats.getNewCount());
        assertEquals(2, stats.getExistingCount());
        assertEquals(stats.getTotalCount(), stats.getNewCount() + stats.getExistingCount());
    }

    @Test
    void reportConditionsAndTitleSurviveTopicEditingAndJsonRoundTrip() {
        var keywords = new ArrayList<>(List.of("HBM"));
        Topic topic = Topic.builder().id(1L).name("HBM 시장").queryText("HBM 수출")
                .requiredKeywords(keywords).optionalKeywords(List.of("HBM4"))
                .excludedKeywords(List.of("채용")).batchSize(100).intervalMinutes(1440).build();
        var item = CollectionRunItem.builder().topic(topic).source(Source.builder().id(2L).build()).build();
        item.captureTopicSnapshot();
        keywords.add("수정한 키워드");
        topic.update("수정한 이름", "수정", List.of("새 키워드"), List.of(), List.of(), 20, 60, false);
        item.captureTopicSnapshot();
        assertEquals("HBM 시장", item.collectionTopic().getName());
        assertEquals(List.of("HBM"), item.collectionTopic().getRequiredKeywords());
        assertEquals(100, item.collectionTopic().getBatchSize());
        var run = CollectionRun.builder().id(42L).items(List.of(item)).build();
        var contexts = List.of(ReportCollectionContext.from(run));
        var converter = new ReportCollectionContextConverter();
        var stored = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(contexts));
        assertEquals(contexts, stored);
        NewsReport report = NewsReport.builder().collectionContexts(stored).reportScope(ReportScope.DAILY)
                .reportDate(LocalDate.of(2026, 9, 8)).build();
        var generatedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        assertEquals("2026-09-08 일일 통합 뉴스 보고서", ReportTitles.forReport(report, "모호한 제목", generatedAt));
        assertEquals("2026-09-08 일일 통합 뉴스 보고서", ReportTitles.forReport(
                NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(report.getReportDate()).build(),
                "모호한 제목", generatedAt));
        assertEquals("HBM 시장 · 2026-09-09 10:00 리포트", ReportTitles.forReport(
                NewsReport.builder().collectionContexts(stored).build(), "모호한 제목", generatedAt));
    }

    @Test
    void structuredContentPreservesValidatedEvidenceAndKeepsLegacyNullDistinct() {
        var content = new ReportContent(List.of("회사는 HBM4 양산을 검토 중이다."),
                List.of(new ReportContent.ImportantEvent("HBM4 계획", "양산 검토", "일정 확인 필요", List.of(501L))),
                List.of(new ReportContent.WatchItem("양산 시기", "아직 확정되지 않았다.", List.of(501L))), List.of());
        var converter = new ReportContentConverter();
        assertEquals(content, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(content)));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals("기존 제목", ReportTitles.forReport(NewsReport.builder().build(), "기존 제목", LocalDateTime.now()));
    }

    private ReportArticleObservation observation(long id, ChangeType type) {
        return new ReportArticleObservation() {
            public Long getArticleId() { return id; }
            public ChangeType getChangeType() { return type; }
        };
    }
}
