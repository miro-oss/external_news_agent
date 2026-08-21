package com.example.be.domain.reports.service;

import java.util.ArrayList;
import java.util.List;

/** Agent와 fallback이 같은 수집 제약 문구를 사용하도록 source note를 한곳에서 만든다. */
final class ReportSourceNotes {

    private ReportSourceNotes() {
    }

    static List<String> from(ReportSourceStats stats) {
        List<String> limitations = new ArrayList<>();
        if (stats.stubExcluded() > 0) {
            limitations.add("STUB 분석 " + stats.stubExcluded() + "건 제외");
        }
        if (stats.evidenceExcluded() > 0) {
            limitations.add("근거 부족 LLM 분석 " + stats.evidenceExcluded() + "건 제외");
        }
        if (stats.paywalled() > 0) {
            limitations.add("페이월 " + stats.paywalled() + "건");
        }
        int otherBlocked = Math.max(stats.blocked() - stats.paywalled(), 0);
        if (otherBlocked > 0) {
            limitations.add("접근 제한 " + otherBlocked + "건");
        }
        if (stats.failed() > 0) {
            limitations.add("수집 실패 " + stats.failed() + "건");
        }
        if (limitations.isEmpty()) {
            return List.of("수집 또는 분석 제외 사항이 없습니다.");
        }
        return List.of("수집 제약: " + String.join(", ", limitations) + ".");
    }
}
