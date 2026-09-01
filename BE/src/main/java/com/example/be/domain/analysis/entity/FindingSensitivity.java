package com.example.be.domain.analysis.entity;

import com.example.be.global.converter.IntegerListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;

@Embeddable
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FindingSensitivity {

    public static final BigDecimal LEGACY_LOW_SCORE = new BigDecimal("16.67");
    public static final BigDecimal LEGACY_MEDIUM_SCORE = new BigDecimal("50.00");
    public static final BigDecimal LEGACY_HIGH_SCORE = new BigDecimal("83.33");

    @Column(name = "sensitivity_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal score;

    @Column(name = "customer_move_score")
    private Integer customerMoveScore;

    @Builder.Default
    @Convert(converter = IntegerListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "customer_move_evidence", nullable = false)
    private List<Integer> customerMoveEvidence = List.of();

    @Column(name = "deal_signal_score")
    private Integer dealSignalScore;

    @Builder.Default
    @Convert(converter = IntegerListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "deal_signal_evidence", nullable = false)
    private List<Integer> dealSignalEvidence = List.of();

    @Column(name = "competitor_threat_score")
    private Integer competitorThreatScore;

    @Builder.Default
    @Convert(converter = IntegerListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "competitor_threat_evidence", nullable = false)
    private List<Integer> competitorThreatEvidence = List.of();

    @Column(name = "industry_shift_score")
    private Integer industryShiftScore;

    @Builder.Default
    @Convert(converter = IntegerListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "industry_shift_evidence", nullable = false)
    private List<Integer> industryShiftEvidence = List.of();

    public FindingSensitivityAxis customerMove() {
        return axis(customerMoveScore, customerMoveEvidence);
    }

    public FindingSensitivityAxis dealSignal() {
        return axis(dealSignalScore, dealSignalEvidence);
    }

    public FindingSensitivityAxis competitorThreat() {
        return axis(competitorThreatScore, competitorThreatEvidence);
    }

    public FindingSensitivityAxis industryShift() {
        return axis(industryShiftScore, industryShiftEvidence);
    }

    public static FindingSensitivity legacy(SensitivityLevel level) {
        BigDecimal legacyScore = switch (level) {
            case LOW -> LEGACY_LOW_SCORE;
            case MEDIUM -> LEGACY_MEDIUM_SCORE;
            case HIGH -> LEGACY_HIGH_SCORE;
        };
        return FindingSensitivity.builder().score(legacyScore).build();
    }

    /** 임계값은 API 계약의 기본값이다. 런타임 계산에는 SensitivityCalculator를 사용한다. */
    public SensitivityLevel defaultLevel() {
        if (score.compareTo(new BigDecimal("70")) >= 0) {
            return SensitivityLevel.HIGH;
        }
        if (score.compareTo(new BigDecimal("40")) >= 0) {
            return SensitivityLevel.MEDIUM;
        }
        return SensitivityLevel.LOW;
    }

    private FindingSensitivityAxis axis(Integer value, List<Integer> evidence) {
        // V31 레거시 백필 행은 총점만 있고 축별 근거가 없다.
        return value == null ? FindingSensitivityAxis.unavailable()
                : new FindingSensitivityAxis(value, evidence);
    }
}
