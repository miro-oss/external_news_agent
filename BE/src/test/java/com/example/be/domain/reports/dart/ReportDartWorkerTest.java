package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.financial.DartFinancialService;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
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

    @Test void pollRecoversMissingAndExpiredWorkButDisabledOrPausedProviderIsNotCalled() {
        when(repository.missing()).thenReturn(List.of(17L));
        worker.poll();
        verify(persistence).recover();
        verify(persistence).enqueue(17L);
        verify(repository, never()).pending(any());
        properties.setApiKey("unit-test-key");
        when(repository.paused(any())).thenReturn(true);
        worker.poll();
        verifyNoInteractions(financials);
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
}
