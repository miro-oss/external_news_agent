package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** DAILY/WEEKLY는 생성 시 고정한 근거와 순서를 그대로 조회/발송한다. */
public final class ReportFindings {
    private ReportFindings() {
    }

    public static List<Finding> load(NewsReport report, FindingRepository repository) {
        return loadVisible(report, repository).findings();
    }

    public static List<Finding> load(NewsReport report, FindingRepository repository, TopicRelevancePolicy policy) {
        return loadVisible(report, repository, policy).findings();
    }

    public static Visible loadVisible(NewsReport report, FindingRepository repository, TopicRelevancePolicy policy) {
        Visible original = loadVisible(report, repository);
        List<Finding> relevant = policy.filterFindings(original.findings());
        return new Visible(relevant, original.filtered() || relevant.size() != original.findings().size());
    }

    public static boolean hasFullText(Finding finding) {
        return finding != null && finding.getArticle() != null && finding.getArticle().hasFullText();
    }

    /** Keep stored DAILY/WEEKLY selection/order, but never expose a finding whose original body is unavailable. */
    public static Visible loadVisible(NewsReport report, FindingRepository repository) {
        if (report.getReportScope() == ReportScope.RUN) {
            List<Finding> all = repository.findForReportByRunId(report.getRunId());
            List<Finding> visible = all.stream().filter(ReportFindings::hasFullText).toList();
            return new Visible(ReportFindingOrder.sort(visible), visible.size() != all.size());
        }
        List<Long> ids = report.getReflectedFindingIds();
        if (ids.isEmpty()) {
            return new Visible(List.of(), false);
        }
        Map<Long, Finding> byId = repository.findForReportByIdIn(ids).stream()
                .filter(ReportFindings::hasFullText)
                .collect(Collectors.toMap(Finding::getId, Function.identity()));
        List<Finding> visible = ids.stream().filter(byId::containsKey).map(byId::get).toList();
        return new Visible(visible, visible.size() != ids.size());
    }

    public record Visible(List<Finding> findings, boolean filtered) {
        public Visible {
            findings = List.copyOf(findings);
        }
    }
}
