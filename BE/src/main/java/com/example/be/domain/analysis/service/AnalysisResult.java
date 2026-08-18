package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;

import java.util.List;

public record AnalysisResult(
        String summary,
        List<FindingKeyPoint> keyPoints,
        String intent,
        Sentiment sentiment,
        RiskLevel riskLevel,
        Relevance relevance,
        String category,
        List<FindingSection> sections
) {
}
