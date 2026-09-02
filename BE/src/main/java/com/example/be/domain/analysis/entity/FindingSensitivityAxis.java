package com.example.be.domain.analysis.entity;

import java.util.List;

/** 축 점수 null은 입력 자료로 판정할 수 없음을 뜻한다. */
public record FindingSensitivityAxis(Integer score, List<Integer> evidenceSentenceIds) {

    public FindingSensitivityAxis {
        evidenceSentenceIds = evidenceSentenceIds == null ? List.of() : List.copyOf(evidenceSentenceIds);
        if (score == null) {
            if (!evidenceSentenceIds.isEmpty()) {
                throw new IllegalArgumentException("unavailable 민감도 축은 근거가 없어야 합니다.");
            }
        } else {
            if (score < 0 || score > 3 || evidenceSentenceIds.isEmpty()) {
                throw new IllegalArgumentException("민감도 축은 0~3점과 하나 이상의 근거가 필요합니다.");
            }
        }
        if (evidenceSentenceIds.size() != evidenceSentenceIds.stream().distinct().count()) {
            throw new IllegalArgumentException("민감도 축 근거는 중복될 수 없습니다.");
        }
        if (evidenceSentenceIds.stream().anyMatch(id -> id == null || id < 0)) {
            throw new IllegalArgumentException("민감도 축 근거는 0-based 문장 인덱스여야 합니다.");
        }
    }

    public static FindingSensitivityAxis unavailable() {
        return new FindingSensitivityAxis(null, List.of());
    }
}
