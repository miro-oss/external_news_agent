package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.*;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

final class ReportInsightTestFixtures {
    static AgentReportInsightRequest.FindingPayload finding(long id) {
        return new AgentReportInsightRequest.FindingPayload(id, id + 100, "기사", "https://example.com/" + id,
                "2026-09-30", "주제", List.of(new AgentReportInsightRequest.ClaimPayload(id + ":0",
                "회사는 생산 계획을 발표했다.", "FACT", null, List.of(0))),
                List.of(new AgentReportInsightRequest.SentencePayload(0, "회사는 생산 계획을 발표했다.")));
    }
    static ReportInsightSnapshotAssembler.Snapshot snapshot(String hash) {
        return new ReportInsightSnapshotAssembler.Snapshot(10L, 20L, hash,
                new AgentReportInsightRequest.ReportPayload(10L, "리포트", "DAILY", LocalDate.of(2026, 9, 30), null),
                List.of(finding(50), finding(40)));
    }
    static AgentReportInsightRequest request() {
        var snapshot = snapshot("a".repeat(64));
        return new AgentReportInsightRequest("test", AgentPlan.PAID, List.of("CHIP_MAKER"), snapshot.report(), snapshot.findings());
    }
    static AgentReportInsightResponse response() {
        return response(List.of(assessment(50, 3, 2, 2), assessment(40, 3, 3, 3)), BigDecimal.ONE);
    }
    static AgentReportInsightResponse.Assessment assessment(long id, Integer directness, Integer impact, Integer urgency) {
        return new AgentReportInsightResponse.Assessment(id, "생산 계획의 확인이 필요하다.", List.of(id + ":0"),
                new AgentReportInsightResponse.Axes(directness, impact, urgency, null));
    }
    static AgentReportInsightResponse response(List<AgentReportInsightResponse.Assessment> assessments, BigDecimal credits) {
        return new AgentReportInsightResponse(List.of(new AgentReportInsightResponse.Insight("CHIP_MAKER", "생산 계획 점검",
                List.of(new AgentReportInsightResponse.Overview("일정 확인이 필요하다.", List.of("50:0"), "계획이 실행되는 경우")),
                assessments, List.of(), List.of())), new AgentReportResponse.Meta("openai", "model", ReportInsightService.PROMPT_VERSION,
                20L, 10L, BigDecimal.ONE, credits, false, false));
    }
}
