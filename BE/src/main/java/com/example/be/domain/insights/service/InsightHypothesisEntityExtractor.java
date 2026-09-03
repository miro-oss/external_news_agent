package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.collection.cluster.DeterministicEntityExtractor;
import com.example.be.domain.issues.entity.NewsIssue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

@Component
public class InsightHypothesisEntityExtractor {

    private final DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();

    public List<String> extract(AgentInsightResponse.Insight insight, NewsIssue issue) {
        String trackingText = Stream.concat(
                        insight.implications().stream()
                                .flatMap(value -> Stream.of(value.assumption(), value.falsifiedBy())),
                        insight.watchNext().stream())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (trackingText.isBlank()) {
            return List.of();
        }

        return List.copyOf(extractor.extractProse(trackingText, issue.getEntities()));
    }
}
