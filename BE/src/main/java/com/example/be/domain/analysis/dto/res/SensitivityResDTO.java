package com.example.be.domain.analysis.dto.res;

import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@JsonPropertyOrder({"score", "level", "axes"})
@Schema(name = "SensitivityResponse", description = "회사 맥락 민감도 총점과 근거 기반 4축 평가")
public record SensitivityResDTO(
        @Schema(minimum = "0", maximum = "100", example = "72.22") BigDecimal score,
        @Schema(allowableValues = {"low", "medium", "high"}) String level,
        Axes axes
) {

    public static SensitivityResDTO of(FindingSensitivity sensitivity, SensitivityLevel level) {
        return new SensitivityResDTO(
                sensitivity.getScore(),
                level.toApiValue(),
                new Axes(
                        Axis.of(sensitivity.customerMove()),
                        Axis.of(sensitivity.dealSignal()),
                        Axis.of(sensitivity.competitorThreat()),
                        Axis.of(sensitivity.industryShift())));
    }

    @JsonPropertyOrder({"customerMove", "dealSignal", "competitorThreat", "industryShift"})
    public record Axes(
            Axis customerMove,
            Axis dealSignal,
            Axis competitorThreat,
            Axis industryShift
    ) {
    }

    @JsonPropertyOrder({"score", "evidenceSentenceIds"})
    public record Axis(
            @Schema(nullable = true, minimum = "0", maximum = "3") Integer score,
            @Schema(description = "기사 상세 sentences.index를 참조하는 0-based 문장 인덱스")
            List<Integer> evidenceSentenceIds
    ) {
        private static Axis of(FindingSensitivityAxis axis) {
            return new Axis(axis.score(), axis.evidenceSentenceIds());
        }
    }
}
