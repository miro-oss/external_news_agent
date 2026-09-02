package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.Sentiment;

import java.util.List;

public record AnalysisResult(
        String summary,
        List<FindingKeyPoint> keyPoints,
        String intent,
        Sentiment sentiment,
        FindingSensitivity sensitivity,
        Relevance relevance,
        String category,
        List<FindingSection> sections,
        AnalysisSource analysisSource,
        List<FindingAnalysisSection> analysisSections,
        FindingEntities entities,
        List<FindingPerspectiveTag> perspectiveTags,
        AnalysisMetadata metadata
) {

    public AnalysisResult {
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        sections = sections == null ? List.of() : List.copyOf(sections);
        analysisSource = analysisSource == null ? AnalysisSource.STUB : analysisSource;
        analysisSections = analysisSections == null ? List.of() : List.copyOf(analysisSections);
        entities = entities == null ? FindingEntities.empty() : entities;
        perspectiveTags = perspectiveTags == null ? List.of() : List.copyOf(perspectiveTags);
        metadata = metadata == null ? AnalysisMetadata.empty() : metadata;
    }

    public AnalysisResult(
            String summary,
            List<FindingKeyPoint> keyPoints,
            String intent,
            Sentiment sentiment,
            FindingSensitivity sensitivity,
            Relevance relevance,
            String category,
            List<FindingSection> sections
    ) {
        this(summary, keyPoints, intent, sentiment, sensitivity, relevance, category, sections,
                AnalysisSource.STUB, List.of(), FindingEntities.empty(), List.of(),
                AnalysisMetadata.empty());
    }

}
