package com.example.be.domain.reports.service;

import com.example.be.global.config.ApiTimeZone;
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
    void boundedCatchupOnlyAttemptsFourCompletedKoreanCalendarWeeksAndIsolatesFailures() {
        var creation = mock(WeeklyReportCreationService.class);
        LocalDate currentMonday = LocalDate.now(ApiTimeZone.ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        when(creation.generate(currentMonday.minusWeeks(1))).thenThrow(new IllegalStateException("isolated"));
        new WeeklyReportScheduler(creation).generateDueReports();
        verify(creation).recoverInterrupted(any());
        var dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(creation, times(4)).generate(dates.capture());
        assertEquals(java.util.List.of(currentMonday.minusWeeks(1), currentMonday.minusWeeks(2),
                currentMonday.minusWeeks(3), currentMonday.minusWeeks(4)), dates.getAllValues());
    }
}
