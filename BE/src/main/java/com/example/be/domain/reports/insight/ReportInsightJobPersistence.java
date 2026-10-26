package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportInsightJobPersistence {
    private final NewsReportRepository reports;
    private final ReportInsightJobRepository jobs;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(long reportId) {
        var report = reports.findByIdForUpdate(reportId).orElse(null);
        if (report == null || !report.isAutomaticInsightsRequested()
                || report.getDeletedAt() != null || report.getReportStatus() == ReportStatus.PENDING) return;
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        for (var audience : Audience.values()) jobs.enqueue(reportId, audience, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(ReportInsightJobRepository.Job job) {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        if (!jobs.claim(job, now)) return false;
        var report = reports.findById(job.reportId()).orElse(null);
        if (report == null || report.getDeletedAt() != null || report.getReportStatus() == ReportStatus.PENDING) {
            jobs.finish(job, false, now);
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(ReportInsightJobRepository.Job job, boolean succeeded) {
        jobs.finish(job, succeeded, LocalDateTime.now(ApiTimeZone.ZONE));
    }
}
