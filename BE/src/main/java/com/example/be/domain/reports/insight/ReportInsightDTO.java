package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.entity.Audience;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReportInsightDTO {
    private ReportInsightDTO() { }
    public record CreateRequest(List<String> audiences) { }
    public record Result(boolean cached, Long reportId, String inputHash, String promptVersion,
            String rubricVersion, int inputFindingCount, List<AudienceInsight> insights) { }
    public record AudienceInsight(Audience audience, String headline, String importance,
            List<AgentReportInsightResponse.Overview> overview, List<Issue> issues, List<Fact> facts,
            List<AgentReportInsightResponse.Implication> implications,
            List<AgentReportInsightResponse.WatchItem> watchItems,
            String llmProvider, String llmModel, OffsetDateTime createdAt) { }
    public record Issue(Long findingId, String importance, String reason, List<String> basisClaimIds,
            AgentReportInsightResponse.Axes axes, int rank) { }
    public record Fact(String id, String text, String claimType, String attributedTo, Long findingId,
            Long articleId, List<Integer> evidenceSentenceIds, String groundedness) { }
}
