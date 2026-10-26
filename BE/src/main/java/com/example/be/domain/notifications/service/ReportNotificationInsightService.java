package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.insight.ReportInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Manual delivery only: reuse the read API's current input/version check, never generate insights. */
@Service
@RequiredArgsConstructor
public class ReportNotificationInsightService {
    private final ReportInsightService insights;

    public ReportInsightDTO.AudienceInsight find(Long reportId, String audience) {
        if (audience == null) return null;
        return insights.get(reportId, audience).insights().getFirst();
    }
}
