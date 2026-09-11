package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportChangesQueryService {
    private final NewsReportRepository reports;
    private final ReportComparisonRepository comparisons;

    @Transactional(readOnly = true)
    public ReportChanges get(long reportId) {
        var report = reports.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        if (report.getReportScope() == ReportScope.RUN) return new ReportChanges(reportId, null, null, null,
                ReportChanges.Status.NOT_APPLICABLE, ReportChanges.Status.NOT_APPLICABLE.message, false, List.of(), List.of());
        var job = comparisons.find(reportId).orElse(null);
        if (job == null) {
            var state = comparisons.findInput(reportId).isPresent() ? ReportChanges.Status.PENDING : ReportChanges.Status.UNAVAILABLE;
            return new ReportChanges(reportId, report.getReportDate(), null, null, state, state.message, false, List.of(), List.of());
        }
        if (job.result().baseReportId() != null && reports.findByIdAndReportStatusNot(
                job.result().baseReportId(), ReportStatus.PENDING).isEmpty()) {
            return job.result().withStatus(ReportChanges.Status.UNAVAILABLE);
        }
        return job.result().withStatus(job.status());
    }
}
