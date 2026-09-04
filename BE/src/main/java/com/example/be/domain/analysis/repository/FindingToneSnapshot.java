package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Sentiment;

import java.util.List;

/** 논조 집계용 조회 값. 본문·요약·엔티티·관점 CLOB과 JPA 엔티티는 적재하지 않는다. */
public record FindingToneSnapshot(
        Long id,
        Long articleId,
        AnalysisSource analysisSource,
        Sentiment sentiment,
        List<FindingKeyPoint> keyPoints,
        List<FindingAnalysisSection> analysisSections
) {

    public List<FindingKeyPoint> effectiveKeyPoints() {
        return FindingKeyPoint.effectivePoints(keyPoints, analysisSections);
    }
}
