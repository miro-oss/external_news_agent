package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.dto.res.ReportDartDTO.FilingRow;
import com.example.be.domain.reports.dto.res.ReportDartDTO.IssueLink;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared public/delivery projection. Removing evidence can never create a company or direct reference. */
public final class ReportIssueCompanyProjection {
    private ReportIssueCompanyProjection() { }

    public static List<IssueLink> storedLinks(NewsReport report, String corpCode) {
        var snapshot = report.getReportIssueCompanySnapshot();
        if (snapshot == null || !snapshot.valid()) return List.of();
        return snapshot.links().stream().filter(link -> corpCode.equals(link.company().corpCode()))
                .map(link -> new IssueLink(link.findingId(), link.articleId(), link.issueId(), link.matchedName(),
                        link.evidenceText(), link.evidenceUrl(), link.rceptNos())).distinct().toList();
    }

    /** Metadata follows the same exclusions as /dart, without fetching or scheduling financial data. */
    public static List<Company> companies(NewsReport report, ReportEventFeedbackProjection.View view) {
        var entries = ReportDartEligibility.companies(report).stream().map(company -> new CompanyAttachment(company,
                "PENDING", null, null, storedLinks(report, company.corpCode()), List.of())).toList();
        return project(report, view, entries).stream().map(CompanyAttachment::company).toList();
    }

    public static List<CompanyAttachment> project(NewsReport report, ReportEventFeedbackProjection.View view,
                                                 List<CompanyAttachment> companies) {
        if (companies == null || companies.isEmpty()) return List.of();
        Map<Long, Long> visibleArticles = new LinkedHashMap<>();
        if (view != null) view.findings().stream()
                .filter(finding -> !view.excludedEvidenceFindingIds().contains(finding.getId())
                        && !report.getCompanySuppressedFindingIds().contains(finding.getId()))
                .forEach(finding -> visibleArticles.put(finding.getId(),
                        finding.getArticle() == null ? null : finding.getArticle().getId()));
        Set<String> selected = visibleSelections(report, view, visibleArticles.keySet());
        List<CompanyAttachment> result = new ArrayList<>();
        for (var company : companies) {
            var links = company.issueLinks().stream().filter(link -> visibleArticles.containsKey(link.findingId())
                    && Long.valueOf(link.articleId()).equals(visibleArticles.get(link.findingId()))).toList();
            if (links.isEmpty() && !selected.contains(company.company().corpCode())) continue;
            result.add(new CompanyAttachment(company.company(), company.status(), company.message(), company.data(), links,
                    filingRows(company.data(), links)));
        }
        return List.copyOf(result);
    }

    private static Set<String> visibleSelections(NewsReport report, ReportEventFeedbackProjection.View view,
                                                 Set<Long> retainedIds) {
        var selected = ReportDartEligibility.selectedCompanies(report).stream().map(Company::corpCode)
                .collect(Collectors.toSet());
        if (view == null) return report.getCompanySuppressedFindingIds().isEmpty() ? selected : Set.of();
        if (!view.sourceFiltered() && !view.excludedEvents() && view.excludedEvidenceFindingIds().isEmpty()
                && report.getCompanySuppressedFindingIds().isEmpty()) return selected;
        Set<Long> topics = new HashSet<>();
        var coverage = report.getReportCoverageSnapshot();
        if (coverage != null) coverage.bindings().stream().filter(binding -> retainedIds.contains(binding.findingId()))
                .map(com.example.be.domain.reports.coverage.ReportCoverageSnapshot.Binding::topicId)
                .filter(java.util.Objects::nonNull).forEach(topics::add);
        // A legacy RUN article's first topic does not prove which topic observed it in that run.
        if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() != null && !retainedIds.isEmpty())
            topics.add(report.getTopicId());
        Set<String> allowed = new HashSet<>();
        if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() != null
                && topics.contains(report.getTopicId()) && report.getTopicDartCompany() != null)
            allowed.add(report.getTopicDartCompany().corpCode());
        if (report.getReportScope() == ReportScope.RUN && report.getCollectionContexts() != null)
            report.getCollectionContexts().forEach(context -> context.topics().stream()
                    .filter(topic -> topics.contains(topic.topicId()) && topic.dartCompany() != null)
                    .forEach(topic -> allowed.add(topic.dartCompany().corpCode())));
        allowed.retainAll(selected);
        return allowed;
    }

    /** The receipt union is company-owned data; relevance is recomputed solely from surviving links. */
    public static List<FilingRow> filingRows(FinancialData data, List<IssueLink> links) {
        if (data == null) return List.of();
        Map<String, Filing> filings = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> roles = new LinkedHashMap<>();
        if (data.disclosures() != null) data.disclosures().stream().limit(5)
                .forEach(filing -> add(filings, roles, filing, "RECENT"));
        if (data.quarters() != null) data.quarters().forEach(quarter -> {
            if (quarter.sources() != null) quarter.sources().forEach(filing -> add(filings, roles, filing, "FINANCIAL_SOURCE"));
        });
        return filings.values().stream().sorted(Comparator.comparing(Filing::filedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Filing::rceptNo))
                .map(filing -> {
                    List<Long> sources = (links == null ? List.<IssueLink>of() : links).stream()
                            .filter(link -> link.rceptNos().contains(filing.rceptNo())).map(IssueLink::findingId).distinct().toList();
                    return new FilingRow(filing, List.copyOf(roles.get(filing.rceptNo())),
                            sources.isEmpty() ? "COMPANY_REFERENCE" : "DIRECT_REFERENCE", sources);
                }).toList();
    }

    private static void add(Map<String, Filing> filings, Map<String, LinkedHashSet<String>> roles, Filing filing, String role) {
        if (filing == null || filing.rceptNo() == null || !filing.rceptNo().matches("[0-9]{14}")) return;
        filings.putIfAbsent(filing.rceptNo(), filing);
        roles.computeIfAbsent(filing.rceptNo(), ignored -> new LinkedHashSet<>()).add(role);
    }
}
