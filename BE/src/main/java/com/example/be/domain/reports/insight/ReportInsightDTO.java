package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.entity.Audience;
import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public final class ReportInsightDTO {
    private ReportInsightDTO() { }
    public record CreateRequest(List<String> audiences) { }
    public record Result(boolean cached, Long reportId, String inputHash, String promptVersion,
            String rubricVersion, int inputFindingCount, List<AudienceInsight> insights) { }
    public record AudienceInsight(Audience audience, String headline, String importance,
            List<AgentReportInsightResponse.Overview> overview, List<Issue> issues, List<Fact> facts,
            List<AgentReportInsightResponse.Implication> implications,
            List<AgentReportInsightResponse.WatchItem> watchItems,
            String llmProvider, String llmModel, OffsetDateTime createdAt) {
        @JsonProperty(value = "briefing", access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "현재 입력과 일치하는 저장 관점 결과의 짧은 원문 주장·의미/조건·다음 확인점. 새 AI 호출 없음")
        public ReportInsightBriefing briefing() { return ReportInsightBriefing.from(this); }
    }
    public record Issue(Long findingId, String importance, String reason, List<String> basisClaimIds,
            AgentReportInsightResponse.Axes axes, int rank) { }
    public record Fact(String id, String text, String claimType, String attributedTo, Long findingId,
            Long articleId, List<Integer> evidenceSentenceIds, String groundedness) { }
}
