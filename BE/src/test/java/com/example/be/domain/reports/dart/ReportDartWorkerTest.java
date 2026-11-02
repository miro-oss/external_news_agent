package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.financial.DartFinancialService;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.comparison.ReportCompleted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDartWorkerTest {
    final ReportDartRepository repository = mock(ReportDartRepository.class);
    final ReportDartPersistence persistence = mock(ReportDartPersistence.class);
    final DartFinancialService financials = mock(DartFinancialService.class);
    final DartProperties properties = new DartProperties();
    final ReportDartWorker worker = new ReportDartWorker(repository, persistence, financials, properties);
    final ReportDartRepository.Job job = new ReportDartRepository.Job(17L, SAMSUNG, 0);

    @Test void completionNeverCallsProviderAndEnqueueFailureDoesNotUndoReport() {
        worker.completed(new ReportCompleted(17L));
        verify(persistence).enqueue(17L);
        doThrow(new IllegalStateException("unavailable")).when(persistence).enqueue(18L);
        assertDoesNotThrow(() -> worker.completed(new ReportCompleted(18L)));
        verifyNoInteractions(financials);
    }

    @Test void unlistedAndMalformedLegacyJobsNeverClaimWorkOrRefreshCompanyData() {
        for (var stockCode : new String[] {null, "", " ", "12345", "1234567", "0068y0"}) {
            var company = new Company("99993210", "비상장 또는 잘못된 종목코드", stockCode);
            worker.process(new ReportDartRepository.Job(17L, company, 0));
            worker.refresh(company);
        }
        verifyNoInteractions(persistence, financials);
    }

    @Test void alphanumericListedCompanyStillFetchesAndRefreshes() {
        var company = new Company("99993210", "영숫자 종목코드 회사", "0068Y0");
        var listedJob = new ReportDartRepository.Job(17L, company, 0);
        when(persistence.claim(eq(listedJob), anyString())).thenReturn(true);
        when(persistence.cache(eq(company.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(persistence.refreshAccess(eq(company.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(financials.fetch(company)).thenReturn(data());

        worker.process(listedJob);
        worker.refresh(company);

        verify(financials, times(2)).fetch(company);
        verify(persistence).complete(eq(listedJob), anyString(), eq(data()), eq(true));
        verify(persistence).refreshCache(eq(company.corpCode()), anyString(), eq(data()));
    }

    @Test void pollHydratesCachedReportsBeforeDisabledOrPausedProviderStopsExternalWork() {
        when(repository.missing(anyLong())).thenReturn(List.of(17L));
        when(repository.reusableReports(anyLong())).thenReturn(List.of(18L));
        worker.poll();
        var order = inOrder(persistence);
        order.verify(persistence).recover();
        order.verify(persistence).enqueue(17L);
        order.verify(persistence).enqueue(18L);
        verify(repository, never()).pending(any());
        properties.setApiKey("unit-test-key");
        when(repository.paused(any())).thenReturn(true);
        worker.poll();
        verify(persistence, times(2)).enqueue(18L);
        verify(repository, never()).pending(any());
        verify(repository, never()).staleCompanies(any(), any());
        verifyNoInteractions(financials);
    }

    @Test void missingRecoveryAdvancesPastUnusablePageWrapsAndSurvivesWorkerRestart() {
        var unusable = java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList();
        when(repository.missing(0L)).thenReturn(unusable);
        when(repository.missing(20L)).thenReturn(List.of(21L));
        when(repository.missing(21L)).thenReturn(List.of());
        doThrow(new IllegalStateException("unusable snapshot")).when(persistence).enqueue(1L);

        worker.poll();
        worker.poll();
        worker.poll();
        worker.poll();

        verify(repository, times(2)).missing(0L);
        verify(repository).missing(20L);
        verify(repository).missing(21L);
        verify(persistence).enqueue(21L);
        // Each worker owns only a scan cursor; idempotent enqueue remains the shared authority.
        var restarted = new ReportDartWorker(repository, persistence, financials, properties);
        restarted.poll();
        restarted.poll();
        verify(repository, times(3)).missing(0L);
        verify(persistence, times(2)).enqueue(21L);
        verifyNoInteractions(financials);
    }

    @Test void cacheRecoveryUsesItsOwnCursorAndWrapsAfterTheLastPage() {
        var unusable = java.util.stream.LongStream.rangeClosed(31, 50).boxed().toList();
        when(repository.reusableReports(0L)).thenReturn(unusable);
        when(repository.reusableReports(50L)).thenReturn(List.of(51L));
        when(repository.reusableReports(51L)).thenReturn(List.of());

        worker.poll();
        worker.poll();
        worker.poll();
        worker.poll();

        verify(repository, times(2)).reusableReports(0L);
        verify(repository).reusableReports(50L);
        verify(repository).reusableReports(51L);
        verify(persistence).enqueue(51L);
        verifyNoInteractions(financials);
    }

    @Test void pollingProcessesMissingDataBeforeRefreshingStaleCompanyCaches() {
        properties.setApiKey("unit-test-key");
        when(repository.pending(any())).thenReturn(List.of(job));
        when(repository.staleCompanies(any(), any())).thenReturn(List.of(HYNIX));
        when(persistence.claim(eq(job), anyString())).thenReturn(true);
        when(persistence.cache(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(data(), false));
        when(persistence.refreshAccess(eq(HYNIX.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(financials.fetch(HYNIX)).thenReturn(data());

        worker.poll();

        var order = inOrder(persistence, financials);
        order.verify(persistence).complete(eq(job), anyString(), eq(data()), eq(false));
        order.verify(financials).fetch(HYNIX);
        order.verify(persistence).refreshCache(eq(HYNIX.corpCode()), anyString(), eq(data()));
    }

    @Test void duplicateClaimAndBusyCompanyCacheDoNotCallProvider() {
        worker.process(job);
        verifyNoInteractions(financials);
        when(persistence.claim(eq(job), anyString())).thenReturn(true);
        when(persistence.cache(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, false));
        worker.process(job);
        verify(persistence).defer(eq(job), anyString());
        verifyNoInteractions(financials);
    }

    @Test void freshCompanyCacheIsCopiedToReportWithoutAnExternalCall() {
        when(persistence.claim(eq(job), anyString())).thenReturn(true);
        when(persistence.cache(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(data(), false));
        worker.process(job);
        verify(persistence).complete(eq(job), anyString(), eq(data()), eq(false));
        verifyNoInteractions(financials);
    }

    @Test void cacheOwnerFetchesOnceAndStoresSnapshotOrSafeFailureClassification() {
        when(persistence.claim(eq(job), anyString())).thenReturn(true);
        when(persistence.cache(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(financials.fetch(SAMSUNG)).thenReturn(data());
        worker.process(job);
        verify(persistence).complete(eq(job), anyString(), eq(data()), eq(true));
        when(financials.fetch(SAMSUNG)).thenThrow(new DartUpstreamException("020"));
        worker.process(job);
        verify(persistence).fail(eq(job), anyString(), eq("020"));
    }

    @Test void cacheRefreshSkipsFreshDataAndAnotherOwnersLease() {
        when(persistence.refreshAccess(eq(SAMSUNG.corpCode()), anyString()))
                .thenReturn(new ReportDartPersistence.CacheAccess(data(), false), new ReportDartPersistence.CacheAccess(null, false));

        worker.refresh(SAMSUNG);
        worker.refresh(SAMSUNG);

        verify(persistence, times(2)).refreshAccess(eq(SAMSUNG.corpCode()), anyString());
        verifyNoMoreInteractions(persistence);
        verifyNoInteractions(financials);
    }

    @Test void cacheRefreshSuccessWritesOnlyTheCompanyCache() {
        when(persistence.refreshAccess(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(financials.fetch(SAMSUNG)).thenReturn(data());

        worker.refresh(SAMSUNG);

        verify(persistence).refreshAccess(eq(SAMSUNG.corpCode()), anyString());
        verify(persistence).refreshCache(eq(SAMSUNG.corpCode()), anyString(), eq(data()));
        verifyNoMoreInteractions(persistence);
    }

    @Test void cacheRefreshFailuresDoNotFailOrRequeueReportAttachments() {
        when(persistence.refreshAccess(eq(SAMSUNG.corpCode()), anyString())).thenReturn(new ReportDartPersistence.CacheAccess(null, true));
        when(financials.fetch(SAMSUNG)).thenThrow(new DartUpstreamException("020"), new IllegalStateException("unavailable"));

        worker.refresh(SAMSUNG);
        worker.refresh(SAMSUNG);

        verify(persistence, times(2)).refreshAccess(eq(SAMSUNG.corpCode()), anyString());
        verify(persistence).failCache(eq(SAMSUNG.corpCode()), anyString(), eq("020"));
        verify(persistence).failCache(eq(SAMSUNG.corpCode()), anyString(), eq("INTERNAL"));
        verifyNoMoreInteractions(persistence);
    }
}
