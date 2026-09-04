package com.example.be.domain.reports.service;

import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DailyReportSchedulerTest {
    @Test
    void reportsBlockedDatesOutsideAutomaticBackfillWindow(CapturedOutput output) {
        DailyReportJdbcRepository repository = mock(DailyReportJdbcRepository.class);
        DailyReportCreationService service = mock(DailyReportCreationService.class);
        LocalDate blocked = LocalDate.now(com.example.be.global.config.ApiTimeZone.ZONE).minusDays(10);
        when(repository.findBlockedDates(any())).thenReturn(List.of(
                new DailyReportJdbcRepository.BlockedDate(blocked, 2)));
        new DailyReportScheduler(repository, service).generateDueReports();
        org.junit.jupiter.api.Assertions.assertTrue(output.getOut().contains("date=" + blocked));
        org.junit.jupiter.api.Assertions.assertTrue(output.getOut().contains("pendingRunCount=2"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getOut().contains("outsideBackfillWindow=true"));
        verify(service, never()).generate(any());
    }
    @Test
    void failedDateDoesNotPreventFollowingDateAndRecoversOldReservations() {
        DailyReportJdbcRepository repository = mock(DailyReportJdbcRepository.class);
        DailyReportCreationService service = mock(DailyReportCreationService.class);
        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate second = first.plusDays(1);
        when(repository.findDueDates(any(), any())).thenReturn(List.of(first, second));
        when(service.generate(first)).thenThrow(new IllegalStateException("isolated error"));
        new DailyReportScheduler(repository, service).generateDueReports();
        verify(service).recoverInterrupted(any());
        verify(service).generate(second);
    }
}
