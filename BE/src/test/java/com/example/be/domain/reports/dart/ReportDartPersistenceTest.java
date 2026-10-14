package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.DartProperties;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDartPersistenceTest {
    final NewsReportRepository reports = mock(NewsReportRepository.class);
    final ReportDartRepository repository = mock(ReportDartRepository.class);
    final DartProperties properties = new DartProperties();
    final ReportDartPersistence persistence = new ReportDartPersistence(reports, repository, properties);

    @Test void registersUniqueFrozenCompaniesAndDoesNotRegisterAggregatedReports() {
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report(ReportScope.RUN, SAMSUNG, HYNIX, SAMSUNG)));
        persistence.enqueue(17L);
        verify(repository).enqueue(eq(17L), eq(SAMSUNG), eq(false), any());
        verify(repository).enqueue(eq(17L), eq(HYNIX), eq(false), any());
        when(reports.findByIdForUpdate(18L)).thenReturn(Optional.of(report(ReportScope.DAILY, SAMSUNG)));
        persistence.enqueue(18L);
        verify(repository, never()).enqueue(eq(18L), any(), anyBoolean(), any());
    }

    @Test void freshCacheSkipsProviderLeaseAndExpiredCacheRequiresExclusiveLease() {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        when(repository.cached(SAMSUNG.corpCode())).thenReturn(Optional.of(new ReportDartRepository.Cached(data(), now.minusHours(23))));
        assertEquals(data(), persistence.cache(SAMSUNG.corpCode(), "token").data());
        verify(repository, never()).claimCache(anyString(), anyString(), any(), any());
        when(repository.cached(SAMSUNG.corpCode())).thenReturn(Optional.of(new ReportDartRepository.Cached(data(), now.minusHours(25))));
        when(repository.claimCache(eq(SAMSUNG.corpCode()), eq("token"), any(), any())).thenReturn(true);
        assertTrue(persistence.cache(SAMSUNG.corpCode(), "token").owner());
    }

    @Test void cacheRefreshedDuringLeaseRaceIsReusedAndLeaseReleased() {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        when(repository.cached(SAMSUNG.corpCode())).thenReturn(Optional.empty(), Optional.of(new ReportDartRepository.Cached(data(), now)));
        when(repository.claimCache(eq(SAMSUNG.corpCode()), eq("token"), any(), any())).thenReturn(true);
        var access = persistence.cache(SAMSUNG.corpCode(), "token");
        assertFalse(access.owner());
        assertEquals(data(), access.data());
        verify(repository).releaseCache(SAMSUNG.corpCode(), "token");
    }

    @Test void lostCacheLeaseCannotPublishLateSnapshot() {
        var job = new ReportDartRepository.Job(17L, SAMSUNG, 0);
        persistence.complete(job, "old-token", data(), true);
        verify(repository, never()).complete(any(), anyString(), any(), any());
        verify(repository).defer(eq(job), eq("old-token"), any(), any());
    }

    @Test void quotaDelaysAllCompaniesAndTransientFailuresHaveBoundedAttempts() {
        var job = new ReportDartRepository.Job(17L, SAMSUNG, 0);
        persistence.fail(job, "token", "020");
        var pausedUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).pauseUntil(pausedUntil.capture());
        assertEquals(LocalDateTime.now(ApiTimeZone.ZONE).toLocalDate().plusDays(1).atTime(0, 5), pausedUntil.getValue());
        verify(repository).fail(eq(job), eq("token"), eq("PENDING"), eq(ReportDartMessages.PENDING), any(), any());
        var lastAttempt = new ReportDartRepository.Job(17L, SAMSUNG, 2);
        persistence.fail(lastAttempt, "last-token", "NETWORK");
        verify(repository).fail(eq(lastAttempt), eq("last-token"), eq("FAILED"), eq(ReportDartMessages.FAILED), any(), any());
    }

    @Test void disabledAndAuthenticationFailuresDoNotBusyRetry() {
        var job = new ReportDartRepository.Job(17L, SAMSUNG, 0);
        persistence.fail(job, "disabled-token", "DISABLED");
        verify(repository).fail(eq(job), eq("disabled-token"), eq("DISABLED"), eq(ReportDartMessages.DISABLED), any(), any());
        persistence.fail(job, "auth-token", "010");
        verify(repository).pauseUntil(argThat(time -> time.isAfter(LocalDateTime.now(ApiTimeZone.ZONE).plusMinutes(59))));
    }
}
