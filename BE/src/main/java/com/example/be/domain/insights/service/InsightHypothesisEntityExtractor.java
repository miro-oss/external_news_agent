package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.collection.cluster.DeterministicEntityExtractor;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

        Topic topic = issue.getTopic();
        List<String> topicKeywords = Stream.concat(
                        listOrEmpty(topic.getRequiredKeywords()).stream(),
                        listOrEmpty(topic.getOptionalKeywords()).stream())
                .toList();
        Set<String> entities = new LinkedHashSet<>(
                extractor.extract(trackingText, null, null, topicKeywords));

        String normalizedText = normalizeForContainment(trackingText);
        listOrEmpty(issue.getEntities()).stream()
                .filter(value -> !normalizeForContainment(value).isBlank())
                .filter(value -> normalizedText.contains(normalizeForContainment(value)))
                .forEach(entities::add);
        return List.copyOf(entities);
    }

    private String normalizeForContainment(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
