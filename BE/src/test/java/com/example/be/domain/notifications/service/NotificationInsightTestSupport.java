package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import java.util.List;

final class NotificationInsightTestSupport {
    static ReportInsightDTO.AudienceInsight insight() {
        return insight(List.of(new Overview("장비 수요 변화 가능성", List.of("11:0"), "투자가 집행되는 경우")), List.of());
    }

    static ReportInsightDTO.AudienceInsight insight(List<Overview> overview, List<ReportInsightDTO.Issue> issues) {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "숨겨진 헤드라인", "high", overview, issues,
                List.of(fact(11L), fact(12L), fact(13L), fact(14L)), List.of(), List.of(), "test", "test", null);
    }

    static ReportInsightDTO.Fact fact(long id) {
        return new ReportInsightDTO.Fact(id + ":0", "확인된 근거", "FACT", null, id, 100 + id, List.of(0), "grounded");
    }
}
