package com.example.be.domain.issues.service;

import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.IssueStatusHistory;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueProjectionServiceTest {

    private final NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueStatusHistoryRepository historyRepository = mock(IssueStatusHistoryRepository.class);
    private final NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
    private final IssueStatusCalculator statusCalculator = mock(IssueStatusCalculator.class);
    private final IssueImportanceCalculator importanceCalculator = mock(IssueImportanceCalculator.class);
    private final IssueProjectionService service = new IssueProjectionService(
            issueRepository,
            issueArticleRepository,
            historyRepository,
            watchRepository,
            statusCalculator,
            importanceCalculator);

    @Test
    void storesHistoryOnlyOnChangeAndAllowsStatusReversal() {
        NewsIssue issue = NewsIssue.builder().id(10L).status(IssueStatus.CORROBORATED).build();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-02T12:00:00+09:00");
        when(statusCalculator.calculate(issue, List.of()))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.DISPUTED, "충돌 확인"))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.DISPUTED, "충돌 확인"))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.CORROBORATED, "정정 반영"));
        when(importanceCalculator.calculate(issue, List.of(), now))
                .thenReturn(new BigDecimal("55.00"));
        NewsWatch disputedWatch = NewsWatch.builder()
                .watchType(WatchType.DISPUTED)
                .issue(issue)
                .expiresAt(LocalDateTime.of(2026, 9, 3, 12, 0))
                .active(true)
                .build();
        when(watchRepository.findByIssueIdAndWatchType(10L, WatchType.DISPUTED))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(disputedWatch))
                .thenReturn(Optional.of(disputedWatch));

        service.recalculate(issue, List.of(), now);
        service.recalculate(issue, List.of(), now);
        service.recalculate(issue, List.of(), now);

        assertEquals(IssueStatus.CORROBORATED, issue.getStatus());
        assertEquals(new BigDecimal("55.00"), issue.getImportanceScore());
        ArgumentCaptor<IssueStatusHistory> histories = ArgumentCaptor.forClass(IssueStatusHistory.class);
        verify(historyRepository, times(2)).save(histories.capture());
        assertEquals(IssueStatus.CORROBORATED, histories.getAllValues().get(0).getFromStatus());
        assertEquals(IssueStatus.DISPUTED, histories.getAllValues().get(0).getToStatus());
        assertEquals(IssueStatus.DISPUTED, histories.getAllValues().get(1).getFromStatus());
        assertEquals(IssueStatus.CORROBORATED, histories.getAllValues().get(1).getToStatus());
        verify(importanceCalculator, times(3)).calculate(any(), any(), any());
        verify(watchRepository).save(any(NewsWatch.class));
        assertFalse(disputedWatch.isActive());
    }

    @Test
    void deactivatesDisputedWatchWhenEvidenceReturnsToCorroborated() {
        NewsIssue issue = NewsIssue.builder().id(10L).status(IssueStatus.DISPUTED).build();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-02T12:00:00+09:00");
        NewsWatch watch = NewsWatch.builder()
                .watchType(WatchType.DISPUTED)
                .issue(issue)
                .expiresAt(LocalDateTime.of(2026, 9, 3, 12, 0))
                .active(true)
                .build();
        when(statusCalculator.calculate(issue, List.of()))
                .thenReturn(new IssueStatusCalculator.Projection(
                        IssueStatus.CORROBORATED, "정정 반영"));
        when(importanceCalculator.calculate(issue, List.of(), now))
                .thenReturn(new BigDecimal("70.00"));
        when(watchRepository.findByIssueIdAndWatchType(10L, WatchType.DISPUTED))
                .thenReturn(Optional.of(watch));

        service.recalculate(issue, List.of(), now);

        assertFalse(watch.isActive());
    }
}
