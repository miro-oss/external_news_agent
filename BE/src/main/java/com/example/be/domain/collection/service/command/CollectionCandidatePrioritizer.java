package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.converter.TopicKeywordFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 주제에 연결된 모든 조합을 합친 뒤 metadataFit 상위 URL을 고른다. */
@Component
@RequiredArgsConstructor
public class CollectionCandidatePrioritizer {

    private static final Comparator<Candidate> PRIORITY = Comparator
            .comparingDouble(Candidate::metadataFit).reversed()
            .thenComparing(Candidate::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::normalizedUrl)
            .thenComparing(candidate -> candidate.batch().itemId());

    private final CollectionPipelineProperties properties;

    public Map<Long, List<CollectedArticle>> prioritize(List<CollectionBatch> batches) {
        Map<Long, List<CollectedArticle>> selectedByItem = new LinkedHashMap<>();
        batches.forEach(batch -> selectedByItem.put(batch.itemId(), new ArrayList<>()));

        Map<Long, List<Candidate>> byTopic = new LinkedHashMap<>();
        for (CollectionBatch batch : batches) {
            candidates(batch).forEach(candidate ->
                    byTopic.computeIfAbsent(batch.topic().getId(), ignored -> new ArrayList<>())
                            .add(candidate));
        }

        for (List<Candidate> topicCandidates : byTopic.values()) {
            Map<String, Candidate> bestByUrl = new LinkedHashMap<>();
            topicCandidates.forEach(candidate -> bestByUrl.merge(
                    candidate.normalizedUrl(), candidate,
                    (left, right) -> PRIORITY.compare(left, right) <= 0 ? left : right));
            Set<String> selectedUrls = bestByUrl.values().stream()
                    .sorted(PRIORITY)
                    .limit(properties.getTopicArticleLimit())
                    .map(Candidate::normalizedUrl)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            topicCandidates.stream()
                    .filter(candidate -> selectedUrls.contains(candidate.normalizedUrl()))
                    .sorted(PRIORITY)
                    .forEach(candidate -> selectedByItem.get(candidate.batch().itemId())
                            .add(candidate.article()));
        }

        Map<Long, List<CollectedArticle>> immutable = new LinkedHashMap<>();
        selectedByItem.forEach((itemId, articles) -> immutable.put(itemId, List.copyOf(articles)));
        return Map.copyOf(immutable);
    }

    private List<Candidate> candidates(CollectionBatch batch) {
        if (batch.failed()
                || !batch.outcome().robots().allowed()
                || batch.outcome().notModified()
                || !batch.outcome().fetch().success()) {
            return List.of();
        }

        Map<String, Candidate> byUrl = new LinkedHashMap<>();
        for (CollectedArticle article : batch.outcome().fetch().articles()) {
            TopicKeywordFilter.MatchResult match = TopicKeywordFilter.evaluate(batch.topic(), article);
            if (!match.matches()) {
                continue;
            }
            String normalizedUrl = ArticleHasher.normalizeUrl(article.canonicalUrl());
            Candidate candidate = new Candidate(
                    batch, article, normalizedUrl, match.metadataFit(), article.publishedAt());
            byUrl.merge(normalizedUrl, candidate,
                    (left, right) -> PRIORITY.compare(left, right) <= 0 ? left : right);
        }
        return byUrl.values().stream()
                .sorted(PRIORITY)
                .toList();
    }

    private record Candidate(
            CollectionBatch batch,
            CollectedArticle article,
            String normalizedUrl,
            double metadataFit,
            OffsetDateTime publishedAt
    ) {
    }
}
