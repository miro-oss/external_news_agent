package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SensitivityCalculatorTest {

    private final SensitivityCalculator calculator = SensitivityCalculator.defaults();

    @Test
    void renormalizesWeightsWhenDealSignalIsUnavailable() {
        FindingSensitivity sensitivity = calculator.calculate(
                axis(3),
                FindingSensitivityAxis.unavailable(),
                axis(2),
                axis(1));

        assertEquals(new BigDecimal("76.19"), sensitivity.getScore());
        assertEquals(SensitivityLevel.HIGH, calculator.level(sensitivity.getScore()));
        assertEquals(null, sensitivity.dealSignal().score());
    }

    @Test
    void distinguishesZeroFromUnavailableAndRequiresEvidence() {
        FindingSensitivity sensitivity = calculator.calculate(
                axis(0), axis(0), axis(0), axis(0));

        assertEquals(new BigDecimal("0.00"), sensitivity.getScore());
        assertEquals(SensitivityLevel.LOW, calculator.level(sensitivity.getScore()));
        assertThrows(IllegalArgumentException.class,
                () -> new FindingSensitivityAxis(2, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FindingSensitivityAxis(null, List.of(0)));
    }

    @Test
    void rejectsAllUnavailableAxes() {
        FindingSensitivityAxis unavailable = FindingSensitivityAxis.unavailable();
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(unavailable, unavailable, unavailable, unavailable));
    }

    @Test
    void honorsConfiguredWeightsAndThresholds() {
        AnalysisSelectionProperties properties = new AnalysisSelectionProperties();
        properties.getSensitivity().setCustomerMoveWeight(new BigDecimal("0.10"));
        properties.getSensitivity().setDealSignalWeight(new BigDecimal("0.20"));
        properties.getSensitivity().setCompetitorThreatWeight(new BigDecimal("0.30"));
        properties.getSensitivity().setIndustryShiftWeight(new BigDecimal("0.40"));
        properties.getSensitivity().setMediumThreshold(new BigDecimal("30"));
        properties.getSensitivity().setHighThreshold(new BigDecimal("80"));
        SensitivityCalculator configured = new SensitivityCalculator(properties);

        FindingSensitivity sensitivity = configured.calculate(axis(3), axis(0), axis(0), axis(0));

        assertEquals(new BigDecimal("10.00"), sensitivity.getScore());
        assertEquals(SensitivityLevel.LOW, configured.level(sensitivity.getScore()));
        assertEquals(SensitivityLevel.HIGH, configured.level(new BigDecimal("80.00")));
    }

    private FindingSensitivityAxis axis(int score) {
        return new FindingSensitivityAxis(score, List.of(0));
    }
}
