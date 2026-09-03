package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** DAILY는 생성 시 고정한 근거와 순서를 그대로 조회/발송한다. */
public final class ReportFindings {
    private ReportFindings() {
    }

    public static List<Finding> load(NewsReport report, FindingRepository repository) {
        if (report.getReportScope() == ReportScope.RUN) {
            return ReportFindingOrder.sort(repository.findForReportByRunId(report.getRunId()));
        }
        List<Long> ids = report.getReflectedFindingIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Finding> byId = repository.findForReportByIdIn(ids).stream()
                .collect(Collectors.toMap(Finding::getId, Function.identity()));
        return ids.stream().filter(byId::containsKey).map(byId::get).toList();
    }
}
