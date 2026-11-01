package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.model.DartModels.Company;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Generation-time identity evidence. Neither reads nor retries may rebuild it from live analysis. */
public record ReportIssueCompanySnapshot(String version, OffsetDateTime capturedAt, List<Link> links) {
    public static final String RULE_VERSION = "report-issue-companies.v1";

    public ReportIssueCompanySnapshot { links = links == null ? List.of() : List.copyOf(links); }

    public record Link(Company company, long findingId, long articleId, Long issueId, String matchedName,
                       String evidenceText, String evidenceUrl, List<String> rceptNos) {
        public Link { rceptNos = rceptNos == null ? List.of() : List.copyOf(rceptNos); }
        private boolean valid() {
            return company != null && company.corpCode() != null && company.corpCode().matches("[0-9]{8}")
                    && company.corpName() != null && !company.corpName().isBlank()
                    && company.stockCode() != null && company.stockCode().matches("[0-9A-Z]{6}|")
                    && findingId > 0 && articleId > 0 && (issueId == null || issueId > 0)
                    && matchedName != null && !matchedName.isBlank() && evidenceText != null && !evidenceText.isBlank()
                    && rceptNos.stream().allMatch(value -> value != null && value.matches("[0-9]{14}"));
        }
    }

    public boolean valid() {
        return RULE_VERSION.equals(version) && capturedAt != null && links.stream().allMatch(Link::valid);
    }

    public List<Company> companies() {
        if (!valid()) return List.of();
        var result = new LinkedHashMap<String, Company>();
        links.forEach(link -> result.putIfAbsent(link.company().corpCode(), link.company()));
        return List.copyOf(result.values());
    }

    public ReportIssueCompanySnapshot reflected(List<Long> findingIds) {
        Set<Long> selected = Set.copyOf(findingIds);
        return new ReportIssueCompanySnapshot(version, capturedAt,
                links.stream().filter(link -> selected.contains(link.findingId())).toList());
    }
}
