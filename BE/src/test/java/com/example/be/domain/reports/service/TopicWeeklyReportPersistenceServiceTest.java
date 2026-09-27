package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.*;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicWeeklyReportPersistenceServiceTest {
    private final DailyReportJdbcRepository lock = mock(DailyReportJdbcRepository.class);
    private final WeeklyReportJdbcRepository weekly = mock(WeeklyReportJdbcRepository.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final TopicRepository topics = mock(TopicRepository.class);
    private final TopicWeeklyReportSelector selector = mock(TopicWeeklyReportSelector.class);
    private final TopicWeeklyReportPersistenceService service = new TopicWeeklyReportPersistenceService(lock, weekly, reports, topics, selector);
    private final LocalDate monday = LocalDate.of(2026, 9, 21);

    @BeforeEach void setup() {
        when(topics.findById(1L)).thenReturn(Optional.of(Topic.builder().id(1L).name("HBM").active(false).build()));
        when(selector.select(anyLong(), anyString(), any())).thenAnswer(call -> new TopicWeeklyReportSelector.Selection(
                new WeeklyReportInput.DailySource(null, call.getArgument(2), "empty", "", null, List.of()), List.of()));
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void rejectsInvalidUnfinishedAndEmptyPeriodsWithoutReserving() {
        var now = monday.plusWeeks(1).atStartOfDay();
        assertThrows(GeneralException.class, () -> service.reserve(0L, monday, now));
        assertThrows(GeneralException.class, () -> service.reserve(1L, monday.plusDays(1), now));
        assertThrows(GeneralException.class, () -> service.reserve(1L, monday.plusWeeks(1), now));
        when(weekly.hasUnfinishedTopicInputs(1L, monday)).thenReturn(true);
        assertEquals("선택한 주제의 수집·분석이 진행 중입니다. 완료 후 다시 시도해 주세요.",
                assertThrows(ReportException.class, () -> service.reserve(1L, monday, now)).getMessage());
        when(weekly.hasUnfinishedTopicInputs(1L, monday)).thenReturn(false);
        assertEquals("선택한 주제와 기간에 보고서를 만들 수 있는 분석 자료가 없습니다.",
                assertThrows(ReportException.class, () -> service.reserve(1L, monday, now)).getMessage());
        verify(reports, never()).saveAndFlush(any());
    }

    @Test void sameIdentityReturnsPendingAndRestoresHiddenCompletedSnapshot() {
        var now = monday.plusWeeks(1).atStartOfDay();
        NewsReport existing = NewsReport.builder().id(42L).topicId(1L).reportStatus(ReportStatus.PENDING).build();
        when(reports.findByReportScopeAndReportDateAndTopicId(ReportScope.WEEKLY, monday, 1L)).thenReturn(Optional.of(existing));
        var pending = service.reserve(1L, monday, now);
        assertEquals(42L, pending.reportId()); assertFalse(pending.owner()); assertFalse(pending.ready());
        existing = NewsReport.builder().id(42L).topicId(1L).topicName("당시 이름").reportStatus(ReportStatus.FALLBACK).build();
        existing.hide(now);
        when(reports.findByReportScopeAndReportDateAndTopicId(ReportScope.WEEKLY, monday, 1L)).thenReturn(Optional.of(existing));
        assertTrue(service.reserve(1L, monday, now).ready());
        assertNull(existing.getDeletedAt()); assertEquals("당시 이름", existing.getTopicName());
        verifyNoInteractions(selector, weekly);
    }

    @Test void usesEligibleDaysWithoutDailyReportsAndFiltersOtherTopicContexts() {
        var run = CollectionRun.builder().id(7L).startedAt(monday.atTime(9, 0)).build();
        var selectedTopic = new CollectionTopicSnapshot(1L, "당시 HBM", null, List.of(), List.of(), List.of(), 10, 60);
        var otherTopic = new CollectionTopicSnapshot(2L, "다른 주제", null, List.of(), List.of(), List.of(), 10, 60);
        run.getItems().add(CollectionRunItem.builder().topicSnapshot(selectedTopic).build());
        run.getItems().add(CollectionRunItem.builder().topicSnapshot(otherTopic).build());
        Finding evidence = Finding.builder().id(9L).run(run).build();
        var source = new WeeklyReportInput.DailySource(null, monday, "HBM 분석", "", null, List.of(9L));
        when(selector.select(1L, "HBM", monday)).thenReturn(new TopicWeeklyReportSelector.Selection(source, List.of(evidence)));
        var reservation = service.reserve(1L, monday, monday.plusWeeks(1).atStartOfDay());
        assertTrue(reservation.owner());
        var captor = org.mockito.ArgumentCaptor.forClass(NewsReport.class); verify(reports).saveAndFlush(captor.capture());
        var saved = captor.getValue();
        assertEquals(List.of(monday), saved.getSourceAnalysisDates()); assertEquals(6, saved.getMissingReportDates().size());
        assertEquals(List.of(), saved.getSourceReportIds()); assertEquals(List.of(), saved.getSourceReportDates());
        assertEquals(0L, saved.getSourceReportCount()); assertEquals(List.of(7L), saved.getSourceRunIds());
        assertEquals(List.of(selectedTopic), saved.getCollectionContexts().getFirst().topics());
        verify(reports, never()).findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any());
    }
}
