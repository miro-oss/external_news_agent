package com.example.be.domain.reports.dart;

import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDartServiceTest {
    final NewsReportRepository reports = mock(NewsReportRepository.class);
    final ReportDartRepository repository = mock(ReportDartRepository.class);
    final ReportDartPersistence persistence = mock(ReportDartPersistence.class);
    final DartProperties properties = new DartProperties();
    final ReportDartService service = new ReportDartService(reports, repository, persistence, properties);

    @Test void readsOnlyFrozenCompaniesInSnapshotOrderAndDeduplicatesCodes() {
        properties.setApiKey("unit-test-key");
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report(ReportScope.RUN, HYNIX, SAMSUNG, HYNIX)));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L,
                new Company(SAMSUNG.corpCode(), "지금 바뀐 회사명", SAMSUNG.stockCode()), "READY", ReportDartMessages.READY, data())));
        var result = service.get(17L);
        assertEquals(List.of(HYNIX, SAMSUNG), result.companies().stream().map(ReportDartDTO.CompanyAttachment::company).toList());
        assertEquals("PENDING", result.companies().getFirst().status());
        assertEquals("READY", result.companies().getLast().status());
        assertEquals("10000000000000001", result.companies().getLast().data().quarters().getFirst().revenue());
        verifyNoInteractions(persistence);
        verify(repository, never()).enqueue(anyLong(), any(), anyBoolean(), any());
    }

    @Test void legacyUnselectedDailyAndWeeklyReportsHaveNoPanelEvenIfKeywordsMentionACompany() {
        for (var report : List.of(report(ReportScope.RUN, (Company) null), report(ReportScope.DAILY, SAMSUNG),
                report(ReportScope.WEEKLY, SAMSUNG), report(ReportScope.RUN))) {
            when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
            assertTrue(service.get(17L).companies().isEmpty());
        }
        verifyNoInteractions(repository, persistence);
    }

    @Test void topicWeeklyUsesReservationCompanyInsteadOfHistoricalCollectionCompanies() {
        var report = topicWeekly(SAMSUNG, ReportStatus.GENERATED);
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(
                new ReportDartRepository.Attachment(17L, SAMSUNG, "READY", ReportDartMessages.READY, data()),
                new ReportDartRepository.Attachment(17L, HYNIX, "READY", ReportDartMessages.READY, data())));

        var result = service.get(17L);

        assertEquals(List.of(SAMSUNG), result.companies().stream().map(ReportDartDTO.CompanyAttachment::company).toList());
        assertEquals(data(), result.companies().getFirst().data());
        verifyNoInteractions(persistence);
    }

    @Test void legacyPendingAndHiddenTopicReportsNeverExposeHistoricalCompanies() {
        var hidden = topicWeekly(SAMSUNG, ReportStatus.GENERATED);
        hidden.hide(java.time.LocalDateTime.now());
        for (var report : List.of(topicWeekly(null, ReportStatus.GENERATED),
                topicWeekly(SAMSUNG, ReportStatus.PENDING), hidden)) {
            assertTrue(service.getStored(report).companies().isEmpty());
        }
        verifyNoInteractions(repository, persistence);
    }

    @Test void missingKeyReturnsDisabledWithoutBlockingReportAndRunningMapsToPending() {
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report(ReportScope.RUN, SAMSUNG)));
        assertEquals("DISABLED", service.get(17L).companies().getFirst().status());
        assertEquals("DART API 키가 설정되지 않았습니다.", service.get(17L).companies().getFirst().message());
        properties.setApiKey("unit-test-key");
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "RUNNING", ReportDartMessages.PENDING, null)));
        assertEquals("PENDING", service.get(17L).companies().getFirst().status());
    }

    @Test void retryReturnsStoredCompletedDataWithoutReplacingIt() {
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report(ReportScope.RUN, SAMSUNG)));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "READY", ReportDartMessages.READY, data())));
        assertEquals(data(), service.retry(17L).companies().getFirst().data());
        verify(persistence).retry(17L);
        verify(repository, never()).complete(any(), anyString(), any(), any());
    }

    @Test void absentPendingAndHiddenReportsUseExistingNotFoundContract() {
        assertThrows(ReportException.class, () -> service.get(404L));
    }

    @Test void notificationReadsExistingReportSnapshotWithoutReloadingOrEnqueueing() {
        var report = report(ReportScope.RUN, SAMSUNG, SAMSUNG);
        when(repository.find(17L)).thenReturn(List.of(
                new ReportDartRepository.Attachment(17L, SAMSUNG, "READY", ReportDartMessages.READY, data()),
                new ReportDartRepository.Attachment(17L, HYNIX, "READY", ReportDartMessages.READY, data())));

        var result = service.getStored(report);

        assertEquals(1, result.companies().size());
        assertEquals(SAMSUNG, result.companies().getFirst().company());
        assertEquals(data(), result.companies().getFirst().data());
        verifyNoInteractions(reports, persistence);
        verify(repository).find(17L);
        verifyNoMoreInteractions(repository);
    }
}
