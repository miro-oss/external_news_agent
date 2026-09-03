package com.example.be.domain.collection.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 한 실행에서 반복되는 주제 어휘를 엔티티 교집합에서 제외한다. */
public final class EntityDocumentFrequencyFilter {

    /** 기사 한 쌍에만 나타나는 엔티티는 사건 신호이므로 흔한 주제 어휘로 보지 않는다. */
    public static final int MIN_COMMON_ENTITY_DOCUMENT_FREQUENCY = 3;

    private EntityDocumentFrequencyFilter() {
    }

    public static Set<String> commonValues(Collection<? extends Set<String>> documents,
                                           int minimumDocumentCount,
                                           int minimumFrequency,
                                           double documentRatio) {
        int cut = Math.max(
                minimumFrequency,
                (int) Math.ceil(documents.size() * documentRatio));
        return commonValues(documents, minimumDocumentCount, cut);
    }

    public static Set<String> commonValues(Collection<? extends Set<String>> documents,
                                           int minimumDocumentCount,
                                           int frequencyCut) {
        if (documents.size() < minimumDocumentCount) {
            return Set.of();
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Set<String> document : documents) {
            for (String value : document) {
                documentFrequency.merge(value, 1, Integer::sum);
            }
        }
        return documentFrequency.entrySet().stream()
                .filter(entry -> entry.getValue() >= frequencyCut)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
