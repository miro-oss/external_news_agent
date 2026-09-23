package com.example.be.domain.reports.service;

import com.example.be.global.config.ApiTimeZone;
import com.example.be.domain.reports.repository.WeeklyReportJdbcRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeeklyReportSchedulerTest {
    @Test
    void boundedCatchupAdvancesPastFailedAndDeferredWeeksAndWrapsToNewlyDueWeeks() {
        var creation = mock(WeeklyReportCreationService.class);
        var reports = mock(WeeklyReportJdbcRepository.class);
        LocalDate currentMonday = LocalDate.now(ApiTimeZone.ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var recent = java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(currentMonday::minusWeeks).toList();
        var older = java.util.stream.IntStream.rangeClosed(5, 8).mapToObj(currentMonday::minusWeeks).toList();
        when(reports.findMissingWeeks(currentMonday, 4)).thenReturn(recent, java.util.List.of(currentMonday.minusWeeks(1)));
        when(reports.findMissingWeeks(currentMonday.minusWeeks(4), 4)).thenReturn(older);
        when(reports.findMissingWeeks(currentMonday.minusWeeks(8), 4)).thenReturn(java.util.List.of());
        when(creation.generate(currentMonday.minusWeeks(1))).thenThrow(new IllegalStateException("isolated"));
        var scheduler = new WeeklyReportScheduler(creation, reports);
        scheduler.generateDueReports();
        scheduler.generateDueReports();
        scheduler.generateDueReports();
        verify(creation, times(3)).recoverInterrupted(any());
        var dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(creation, times(9)).generate(dates.capture());
        var expected = new java.util.ArrayList<>(recent);
        expected.addAll(older);
        expected.add(currentMonday.minusWeeks(1));
        assertEquals(expected, dates.getAllValues());
    }

    @Test
    void restartDerivesRemainingWorkFromSavedReportsAndEmptyHistoryCreatesNothing() {
        var creation = mock(WeeklyReportCreationService.class);
        var reports = mock(WeeklyReportJdbcRepository.class);
        LocalDate currentMonday = LocalDate.now(ApiTimeZone.ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        when(reports.findMissingWeeks(currentMonday, 4)).thenReturn(java.util.List.of(currentMonday.minusWeeks(12)), java.util.List.of());
        new WeeklyReportScheduler(creation, reports).generateDueReports();
        new WeeklyReportScheduler(creation, reports).generateDueReports();
        verify(creation).generate(currentMonday.minusWeeks(12));
        verify(creation, times(1)).generate(any());
    }
}
