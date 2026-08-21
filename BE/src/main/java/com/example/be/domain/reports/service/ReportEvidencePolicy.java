package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import org.springframework.util.StringUtils;

import java.util.List;

/** 최종 보고서에 사용할 수 있는 근거 연결만 남기는 공통 정책이다. */
final class ReportEvidencePolicy {

    private ReportEvidencePolicy() {
    }

    static List<FindingKeyPoint> supportedKeyPoints(Finding finding) {
        return finding.getEffectiveKeyPoints().stream()
                .filter(ReportEvidencePolicy::isSupported)
                .toList();
    }

    static boolean hasSupportedEvidence(Finding finding) {
        return !supportedKeyPoints(finding).isEmpty();
    }

    static String supportedSummary(Finding finding) {
        return StringUtils.hasText(finding.getSummary()) ? finding.getSummary().trim() : "";
    }

    private static boolean isSupported(FindingKeyPoint point) {
        return point != null
                && StringUtils.hasText(point.text())
                && point.evidence() != null
                && !point.evidence().isEmpty()
                && ("grounded".equals(point.groundedness()) || "weak".equals(point.groundedness()));
    }
}
