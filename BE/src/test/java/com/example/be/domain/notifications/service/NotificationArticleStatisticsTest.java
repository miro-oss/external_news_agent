package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository.ReportArticleObservation;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class NotificationArticleStatisticsTest {
    private final CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
    private final NotificationArticleStatistics statistics = new NotificationArticleStatistics(observations);

    @Test
    void collectionTotalsRemainAvailableWithoutCoverageOrKeywordSnapshots() {
        var report = NewsReport.builder().reportScope(ReportScope.DAILY).sourceRunIds(List.of(10L, 20L)).build();
        when(observations.findReportArticleObservations(List.of(10L, 20L))).thenReturn(List.of(
                observation(1L, ChangeType.NEW), observation(1L, ChangeType.UNCHANGED),
                observation(2L, ChangeType.UPDATED), observation(3L, ChangeType.UNCHANGED)));

        assertEquals("수집 기사 3건 · 신규 1건 · 기존 2건", statistics.summary(report));
    }

    @Test
    void runUsesItsOwnObservationsRatherThanSelectedIssueCount() {
        var report = NewsReport.builder().reportScope(ReportScope.RUN)
                .run(com.example.be.domain.collection.entity.CollectionRun.builder().id(42L).build()).build();
        when(observations.findReportArticleObservations(List.of(42L))).thenReturn(List.of(
                observation(1L, ChangeType.NEW), observation(2L, ChangeType.NEW)));

        assertEquals("수집 기사 2건 · 신규 2건 · 기존 0건", statistics.summary(report));
        verify(observations).findReportArticleObservations(List.of(42L));
    }

    private ReportArticleObservation observation(long id, ChangeType type) {
        return new ReportArticleObservation() {
            public Long getArticleId() { return id; }
            public ChangeType getChangeType() { return type; }
        };
    }
}
