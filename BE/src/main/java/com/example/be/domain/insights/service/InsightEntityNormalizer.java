package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.investigation.InvestigationQueryNormalizer;
import com.example.be.domain.collection.cluster.DeterministicEntityExtractor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class InsightEntityNormalizer {

    private final DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();

    public Set<String> normalize(Collection<String> values) {
        return extractor.canonicalizeEntities(values).stream()
                .map(InvestigationQueryNormalizer::normalizeEntity)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }
}
