package com.example.be.domain.reports.comparison;

import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import java.time.LocalDate;
import java.util.List;

final class ComparisonFixtures {
    static ReportComparisonSnapshot snapshot(ReportChanges.Side... sides) {
        return new ReportComparisonSnapshot(1, List.of(sides).stream().map(side ->
                new ReportComparisonSnapshot.Issue(side.claims().getFirst().evidence().getFirst().findingId(),
                        "analysis-hash", "a1.ko.v1", "2026-09-09T10:00", side)).toList(), scopes("HBM"));
    }
    static List<ReportCollectionContext> scopes(String query) {
        return List.of(new ReportCollectionContext(10L, List.of(new CollectionTopicSnapshot(
                1L, "반도체", query, List.of(), List.of(), List.of(), 100, 1440))));
    }
    static ReportChanges.Side side(long issueId, long findingId, String claim) {
        return new ReportChanges.Side(issueId, 1L, "B사 양산 일정", claim,
                List.of(new ReportChanges.Claim("finding-" + findingId + "-claim-0", claim,
                        List.of(new ReportChanges.Evidence(findingId, findingId + 100, findingId + 200,
                                "당시 기사 제목", "https://example.com/news/" + findingId, 0, claim)))));
    }
    static ComparisonWork work(ReportComparisonSnapshot previous, ReportComparisonSnapshot current,
                               ComparisonWork.IdentityLink... links) {
        return new ComparisonWork(101L, LocalDate.of(2026, 9, 10), 100L, LocalDate.of(2026, 9, 9),
                previous, current, List.of(links));
    }
}
