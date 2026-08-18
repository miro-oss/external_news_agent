package com.example.be.domain.analysis.entity;

import java.util.Arrays;

/** 분석 enum의 외부 API 문자열 계약. */
public interface ApiValue {

    String toApiValue();

    static <T extends Enum<T> & ApiValue> T parse(T[] values, String value, String label) {
        return Arrays.stream(values)
                .filter(candidate -> candidate.toApiValue().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 " + label + "입니다."));
    }
}
