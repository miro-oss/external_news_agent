package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SensitivityCalculator {

    private static final BigDecimal MAX_AXIS_SCORE = BigDecimal.valueOf(3);
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final AnalysisSelectionProperties properties;

    public static SensitivityCalculator defaults() {
        return new SensitivityCalculator(new AnalysisSelectionProperties());
    }

    public FindingSensitivity calculate(FindingSensitivityAxis customerMove,
                                        FindingSensitivityAxis dealSignal,
                                        FindingSensitivityAxis competitorThreat,
                                        FindingSensitivityAxis industryShift) {
        AnalysisSelectionProperties.Sensitivity config = properties.getSensitivity();
        List<WeightedAxis> axes = List.of(
                new WeightedAxis(customerMove, config.getCustomerMoveWeight()),
                new WeightedAxis(dealSignal, config.getDealSignalWeight()),
                new WeightedAxis(competitorThreat, config.getCompetitorThreatWeight()),
                new WeightedAxis(industryShift, config.getIndustryShiftWeight()));
        BigDecimal availableWeight = axes.stream()
                .filter(axis -> axis.value().score() != null)
                .map(WeightedAxis::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (availableWeight.signum() == 0) {
            throw new IllegalArgumentException("민감도 축은 하나 이상 판정 가능해야 합니다.");
        }
        BigDecimal weighted = axes.stream()
                .filter(axis -> axis.value().score() != null)
                .map(axis -> axis.weight().multiply(BigDecimal.valueOf(axis.value().score())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = weighted.multiply(PERCENT)
                .divide(availableWeight.multiply(MAX_AXIS_SCORE), 2, RoundingMode.HALF_UP);
        return FindingSensitivity.builder()
                .score(score)
                .customerMoveScore(customerMove.score())
                .customerMoveEvidence(customerMove.evidenceSentenceIds())
                .dealSignalScore(dealSignal.score())
                .dealSignalEvidence(dealSignal.evidenceSentenceIds())
                .competitorThreatScore(competitorThreat.score())
                .competitorThreatEvidence(competitorThreat.evidenceSentenceIds())
                .industryShiftScore(industryShift.score())
                .industryShiftEvidence(industryShift.evidenceSentenceIds())
                .build();
    }

    public SensitivityLevel level(BigDecimal score) {
        if (score.compareTo(properties.getSensitivity().getHighThreshold()) >= 0) {
            return SensitivityLevel.HIGH;
        }
        if (score.compareTo(properties.getSensitivity().getMediumThreshold()) >= 0) {
            return SensitivityLevel.MEDIUM;
        }
        return SensitivityLevel.LOW;
    }

    public boolean isHigh(BigDecimal score) {
        return level(score) == SensitivityLevel.HIGH;
    }

    public BigDecimal mediumThreshold() {
        return properties.getSensitivity().getMediumThreshold();
    }

    public BigDecimal highThreshold() {
        return properties.getSensitivity().getHighThreshold();
    }

    private record WeightedAxis(FindingSensitivityAxis value, BigDecimal weight) {
    }
}
