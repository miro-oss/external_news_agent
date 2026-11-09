package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.topics.entity.TopicEditorialFocus;
import com.example.be.global.config.ApiTimeZone;
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

/** 주제에 연결된 모든 조합을 합친 뒤 topicFit 상위 URL을 고른다. */
@Component
@RequiredArgsConstructor
public class CollectionCandidatePrioritizer {

    private static final Comparator<Candidate> PRIORITY = Comparator
            .comparingDouble(Candidate::topicFit).reversed()
            .thenComparing(Candidate::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::normalizedUrl)
            .thenComparing(candidate -> candidate.batch().itemId());

    private final CollectionPipelineProperties properties;
    private final TopicFitScorer topicFitScorer;

    public Map<Long, List<CollectedArticle>> prioritize(List<CollectionBatch> batches) {
        return prioritize(batches, OffsetDateTime.now(ApiTimeZone.ZONE));
    }

    Map<Long, List<CollectedArticle>> prioritize(List<CollectionBatch> batches, OffsetDateTime now) {
        Map<Long, List<CollectedArticle>> selectedByItem = new LinkedHashMap<>();
        batches.forEach(batch -> selectedByItem.put(batch.itemId(), new ArrayList<>()));

        Map<Long, List<Candidate>> byTopic = new LinkedHashMap<>();
        for (CollectionBatch batch : batches) {
            candidates(batch, now).forEach(candidate ->
                    byTopic.computeIfAbsent(batch.topic().getId(), ignored -> new ArrayList<>())
                            .add(candidate));
        }

        for (List<Candidate> topicCandidates : byTopic.values()) {
            Map<String, Candidate> bestByUrl = new LinkedHashMap<>();
            topicCandidates.forEach(candidate -> bestByUrl.merge(
                    candidate.normalizedUrl(), candidate,
                    (left, right) -> PRIORITY.compare(left, right) <= 0 ? left : right));
            Set<String> selectedUrls = select(bestByUrl.values().stream().sorted(PRIORITY).toList()).stream()
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

    private List<Candidate> select(List<Candidate> ordered) {
        int limit = properties.getTopicArticleLimit();
        if (ordered.size() <= limit || !TopicEditorialFocus.isAiTechnology(ordered.getFirst().batch().topic())) {
            return ordered.stream().limit(limit).toList();
        }
        // Each connected source gets its best match before a prolific source consumes another slot.
        Map<Long, List<Candidate>> bySource = new LinkedHashMap<>();
        ordered.forEach(candidate -> bySource.computeIfAbsent(candidate.batch().source().getId(),
                ignored -> new ArrayList<>()).add(candidate));
        List<Candidate> selected = new ArrayList<>();
        for (int round = 0; selected.size() < limit; round++) {
            int index = round;
            List<Candidate> next = bySource.values().stream().filter(values -> values.size() > index)
                    .map(values -> values.get(index)).sorted(PRIORITY).toList();
            if (next.isEmpty()) break;
            next.stream().limit(limit - selected.size()).forEach(selected::add);
        }
        return selected;
    }

    private List<Candidate> candidates(CollectionBatch batch, OffsetDateTime now) {
        if (batch.failed()
                || !batch.outcome().robots().allowed()
                || batch.outcome().notModified()
                || !batch.outcome().fetch().success()) {
            return List.of();
        }

        Map<String, Candidate> byUrl = new LinkedHashMap<>();
        for (CollectedArticle article : batch.outcome().fetch().articles()) {
            // Some technical RSS feeds contain their whole archive. Unknown dates remain unknown.
            if (TopicEditorialFocus.isAiTechnology(batch.topic()) && article.publishedAt() != null
                    && article.publishedAt().isBefore(now.minusDays(properties.getTechnologyLookbackDays()))) {
                continue;
            }
            var match = topicFitScorer.evaluate(batch.topic(), batch.source(), article);
            if (!match.matches()) {
                continue;
            }
            String normalizedUrl = ArticleHasher.normalizeUrl(article.canonicalUrl());
            Candidate candidate = new Candidate(
                    batch, article, normalizedUrl, match.topicFit(), article.publishedAt());
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
            double topicFit,
            OffsetDateTime publishedAt
    ) {
    }
}
