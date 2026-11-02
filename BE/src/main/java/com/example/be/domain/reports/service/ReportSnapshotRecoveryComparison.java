package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportComparisonEngine;
import com.example.be.domain.reports.comparison.ReportComparisonPersistence;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/** Recovery does not repeat paid or uncertain provider calls; unsupported semantic changes stay undecided. */
@Service
@RequiredArgsConstructor
public class ReportSnapshotRecoveryComparison {
    private final ReportComparisonPersistence persistence;
    private final ReportComparisonRepository repository;
    private final ReportComparisonEngine engine;

    public ReportChanges.Status complete(long reportId) {
        persistence.enqueue(reportId);
        var claimed = persistence.claim(reportId);
        if (claimed.isPresent()) {
            try {
                var result = engine.prepare(claimed.get().work()).result();
                var notes = new ArrayList<>(result.notes());
                notes.add(ReportSnapshotRecoveryService.RECOVERED
                        + " 동일 주장과 새 포함을 비교했으며 추가 의미 판단이 필요한 내용은 보류했습니다.");
                persistence.finish(reportId, new ReportChanges(result.reportId(), result.reportDate(), result.baseReportId(),
                        result.baseReportDate(), result.status(), result.message(), result.scopeChanged(), notes,
                        result.items(), result.periods(), result.keywordChanges()));
            } catch (RuntimeException error) {
                persistence.finish(reportId, claimed.get().result().withStatus(ReportChanges.Status.FAILED));
                throw new IllegalStateException("복원 비교에 실패했습니다. reportId=" + reportId, error);
            }
        }
        return repository.find(reportId).map(ReportComparisonRepository.Job::status).orElse(ReportChanges.Status.UNAVAILABLE);
    }
}
