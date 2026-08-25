package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;

/**
 * 보고서 본문에 찍는 한글 표시 라벨.
 *
 * <p>enum의 {@code toApiValue()}는 API 계약이라 바꿀 수 없다. 그 값을 그대로 본문에 쓰면 보고서를
 * 읽는 사람이 {@code high}, {@code important}를 만나게 되므로, 표시용 이름을 여기서 따로 만든다.
 * 화면(FE)이 쓰는 라벨과 같은 말을 써야 같은 값이 두 이름으로 불리지 않는다.
 */
final class ReportLabels {

    private ReportLabels() {
    }

    static String risk(RiskLevel value) {
        return switch (value) {
            case HIGH -> "높음";
            case MEDIUM -> "보통";
            case LOW -> "낮음";
        };
    }

    static String relevance(Relevance value) {
        return switch (value) {
            case IMPORTANT -> "중요";
            case WATCH -> "관찰";
            case REFERENCE -> "참고";
        };
    }
}
