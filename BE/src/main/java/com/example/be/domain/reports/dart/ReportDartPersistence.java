package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportDartPersistence {
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final Duration LEASE = Duration.ofMinutes(30);
    private final NewsReportRepository reports;
    private final ReportDartRepository repository;
    private final DartProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(long reportId) {
        var report = reports.findByIdForUpdate(reportId).orElse(null);
        if (report == null) return;
        var now = now();
        ReportDartEligibility.companies(report).forEach(company -> enqueueWithCachedData(reportId, company, now));
    }

    @Transactional
    public void retry(long reportId) {
        var report = reports.findByIdForUpdate(reportId)
                .filter(value -> value.getDeletedAt() == null && value.getReportStatus() != ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        var companies = ReportDartEligibility.companies(report);
        if (companies.isEmpty()) return;
        var now = now();
        companies.forEach(company -> enqueueWithCachedData(reportId, company, now));
        repository.retry(reportId, properties.isEnabled(), now);
    }

    private void enqueueWithCachedData(long reportId, Company company, LocalDateTime now) {
        repository.enqueue(reportId, company, properties.isEnabled(), now);
        // A cache's age controls background refresh, not whether an existing snapshot can be displayed.
        repository.cached(company.corpCode())
                .ifPresent(cached -> repository.attachCached(reportId, company.corpCode(), cached.data(), now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(ReportDartRepository.Job job, String token) {
        var report = reports.findById(job.reportId()).orElse(null);
        if (report == null || ReportDartEligibility.companies(report).stream()
                .noneMatch(company -> company.corpCode().equals(job.company().corpCode()))) return false;
        var now = now();
        return repository.claim(job, token, now, now.plus(LEASE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recover() { repository.recover(now(), properties.isEnabled()); }

    record CacheAccess(FinancialData data, boolean owner) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CacheAccess cache(String corpCode, String token) {
        return accessCache(corpCode, token, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CacheAccess refreshAccess(String corpCode, String token) {
        return accessCache(corpCode, token, true);
    }

    private CacheAccess accessCache(String corpCode, String token, boolean requireFresh) {
        var now = now();
        var cached = repository.cached(corpCode).filter(value -> !requireFresh || value.cachedAt().plus(CACHE_TTL).isAfter(now));
        if (cached.isPresent()) return new CacheAccess(cached.get().data(), false);
        if (!repository.claimCache(corpCode, token, now, now.plus(LEASE))) return new CacheAccess(null, false);
        // A different instance may have populated the cache between our first read and lease acquisition.
        cached = repository.cached(corpCode).filter(value -> !requireFresh || value.cachedAt().plus(CACHE_TTL).isAfter(now));
        if (cached.isPresent()) {
            repository.releaseCache(corpCode, token);
            return new CacheAccess(cached.get().data(), false);
        }
        return new CacheAccess(null, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(ReportDartRepository.Job job, String token, FinancialData data, boolean cacheOwner) {
        var now = now();
        if (cacheOwner && !repository.saveCache(job.company().corpCode(), token, data, now)) {
            repository.defer(job, token, now.plusSeconds(30), now);
            return;
        }
        repository.complete(job, token, data, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshCache(String corpCode, String token, FinancialData data) {
        repository.saveCache(corpCode, token, data, now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failCache(String corpCode, String token, String upstreamStatus) {
        var now = now();
        var retryAt = now.plusMinutes(5);
        if ("020".equals(upstreamStatus)) {
            retryAt = now.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(5);
            repository.pauseUntil(retryAt);
        } else if (java.util.Set.of("010", "011", "012", "901").contains(upstreamStatus)) {
            retryAt = now.plusHours(1);
            repository.pauseUntil(retryAt);
        }
        // Retain the last successful payload and its timestamp while delaying the next refresh.
        repository.deferCache(corpCode, token, retryAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(ReportDartRepository.Job job, String token) {
        var now = now();
        repository.defer(job, token, now.plusSeconds(30), now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(ReportDartRepository.Job job, String token, String upstreamStatus) {
        var now = now();
        repository.releaseCache(job.company().corpCode(), token);
        String status = job.attempts() + 1 >= ReportDartRepository.MAX_ATTEMPTS ? "FAILED" : "PENDING";
        var retryAt = now.plusMinutes(1L << Math.min(job.attempts(), 2));
        if ("DISABLED".equals(upstreamStatus)) {
            status = "DISABLED";
        } else if ("020".equals(upstreamStatus)) {
            retryAt = now.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(5);
            repository.pauseUntil(retryAt);
        } else if (java.util.Set.of("010", "011", "012", "901").contains(upstreamStatus)) {
            retryAt = now.plusHours(1);
            repository.pauseUntil(retryAt);
        }
        repository.fail(job, token, status, "DISABLED".equals(status) ? ReportDartMessages.DISABLED
                : "FAILED".equals(status) ? ReportDartMessages.FAILED : ReportDartMessages.PENDING, retryAt, now);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
