package com.example.be.domain.analysis.entity;

import java.util.List;

/** 제목을 보존한 Agent 구조화 분석 section. */
public record FindingAnalysisSection(String heading, List<FindingAnalysisBullet> bullets) {

    public FindingAnalysisSection {
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
    }
}
