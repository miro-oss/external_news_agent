package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import org.springframework.util.StringUtils;

import java.util.List;

/** 분석 결과에서 외부 출력에 사용할 수 있는 근거 연결만 남기는 공통 정책이다. */
public final class FindingEvidencePolicy {

    private FindingEvidencePolicy() {
    }

    public static List<FindingKeyPoint> supportedKeyPoints(Finding finding) {
        return finding.getEffectiveKeyPoints().stream()
                .filter(FindingEvidencePolicy::isSupported)
                .toList();
    }

    public static boolean hasSupportedEvidence(Finding finding) {
        return !supportedKeyPoints(finding).isEmpty();
    }

    /** 요약은 별도 claim 단위 근거 계약이 없어 지원 여부를 뜻하지 않으며, 표시용으로만 정규화한다. */
    public static String reportSummary(Finding finding) {
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
