package com.example.be.domain.analysis.agent;

import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;

import java.math.BigDecimal;
import java.util.List;

public final class AgentSensitivityFixtures {

    private AgentSensitivityFixtures() {
    }

    public static AgentAnalyzeResponse.Sensitivity analyze(int score) {
        AgentAnalyzeResponse.SensitivityAxis axis =
                new AgentAnalyzeResponse.SensitivityAxis(score, List.of(1));
        return new AgentAnalyzeResponse.Sensitivity(
                axis,
                new AgentAnalyzeResponse.SensitivityAxis(null, List.of()),
                axis,
                axis);
    }

    public static AgentReportRequest.SensitivityPayload report(int score, String level) {
        AgentReportRequest.SensitivityAxisPayload axis =
                new AgentReportRequest.SensitivityAxisPayload(score, List.of(0));
        BigDecimal total = BigDecimal.valueOf(score)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP);
        return new AgentReportRequest.SensitivityPayload(
                total,
                level,
                new AgentReportRequest.SensitivityAxesPayload(
                        axis,
                        new AgentReportRequest.SensitivityAxisPayload(null, List.of()),
                        axis,
                        axis));
    }
}
