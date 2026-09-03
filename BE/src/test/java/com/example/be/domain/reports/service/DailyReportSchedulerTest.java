package com.example.be.domain.reports.service;

import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyReportSchedulerTest {
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
