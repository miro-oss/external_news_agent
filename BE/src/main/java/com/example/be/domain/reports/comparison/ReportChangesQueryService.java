package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.global.database.OracleInClause;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ReportChangesQueryService {
    private final NewsReportRepository reports;
    private final ReportComparisonRepository comparisons;
    private final FindingRepository findings;

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
        var base = job.result().baseReportId() == null ? null : reports.findByIdAndReportStatusNot(
                job.result().baseReportId(), ReportStatus.PENDING).orElse(null);
        if (job.result().baseReportId() != null && base == null) {
            return job.result().withStatus(ReportChanges.Status.UNAVAILABLE);
        }
        if (job.status() == ReportChanges.Status.READY) {
            Set<Long> required = new LinkedHashSet<>(report.getReflectedFindingIds());
            if (base != null) required.addAll(base.getReflectedFindingIds());
            job.result().items().stream().flatMap(item -> Stream.of(item.previous(), item.current()))
                    .filter(Objects::nonNull).flatMap(side -> side.claims().stream())
                    .flatMap(claim -> claim.evidence().stream()).map(ReportChanges.Evidence::findingId)
                    .forEach(required::add);
            Set<Long> available = OracleInClause.batches(required).stream()
                    .flatMap(ids -> findings.findForReportByIdIn(ids).stream())
                    .filter(ReportFindings::hasFullText).map(Finding::getId).collect(Collectors.toSet());
            if (!available.containsAll(required)) {
                // Check current visibility only; historical quoted text always stays in its saved snapshot.
                var result = job.result();
                var state = ReportChanges.Status.UNAVAILABLE;
                return new ReportChanges(result.reportId(), result.reportDate(), result.baseReportId(),
                        result.baseReportDate(), state, state.message, false, List.of(), List.of());
            }
        }
        return job.result().withStatus(job.status());
    }
}
