package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.agent.dto.AgentFeedbackExample;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.feedback.model.FeedbackModels.Article;
import com.example.be.domain.feedback.model.FeedbackModels.Category;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Reuses durable reviewed errors automatically, including reviews saved before this feature. */
@Service
@RequiredArgsConstructor
public class FeedbackLearningService {
    public static final int MAX_EXAMPLES = 5;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public List<AgentFeedbackExample> forTopic(Topic topic, LocalDateTime asOf, Set<Category> categories) {
        if (topic == null) return List.of();
        return forSnapshots(List.of(CollectionTopicSnapshot.capture(topic)), asOf, categories);
    }

    @Transactional(readOnly = true)
    public List<AgentFeedbackExample> forSnapshots(List<CollectionTopicSnapshot> topics, LocalDateTime asOf,
                                                  Set<Category> categories) {
        if (topics == null || topics.isEmpty() || asOf == null || categories == null) return List.of();
        var allowed = categories.stream().filter(category -> category != Category.PREFERENCE).sorted().toList();
        if (allowed.isEmpty()) return List.of();
        Map<Long, Scope> scopes = new LinkedHashMap<>();
        Set<Long> ambiguous = new java.util.HashSet<>();
        for (var topic : topics) {
            if (topic == null || topic.topicId() == null || topic.topicId() < 1) continue;
            var scope = Scope.from(topic);
            var previous = scopes.putIfAbsent(topic.topicId(), scope);
            if (previous != null && !previous.equals(scope)) ambiguous.add(topic.topicId());
        }
        // A report spanning different definitions of the same topic has no single learning scope.
        ambiguous.forEach(scopes::remove);
        if (scopes.isEmpty()) return List.of();
        List<Object> arguments = new ArrayList<>(allowed.stream().map(Enum::name).toList());
        arguments.add(Timestamp.valueOf(asOf));
        arguments.addAll(scopes.keySet());
        String sql = """
                SELECT f.id, f.category, f.input_json, f.review_json
                FROM news_feedback f JOIN news_reports report ON report.id=f.report_id
                WHERE f.capability_id IS NULL AND f.recipient_id IS NULL AND f.item_id IS NULL
                  AND f.event_key IS NOT NULL AND f.status='COMPLETED' AND f.verdict='CONFIRMED_ERROR'
                  AND report.deleted_at IS NULL AND f.category IN (%s) AND f.finished_at<=?
                  AND JSON_VALUE(f.review_json, '$.meta.mock')='false'
                  AND JSON_VALUE(f.review_json, '$.meta.truncated')='false'
                  AND EXISTS (SELECT 1 FROM JSON_TABLE(f.input_json, '$.event.topics[*]'
                    COLUMNS (topic_id NUMBER PATH '$.id')) jt WHERE jt.topic_id IN (%s))
                ORDER BY f.finished_at DESC, f.id DESC
                """.formatted(placeholders(allowed.size()), placeholders(scopes.size()));
        // Scope filtering precedes the limit, so other topics and obsolete settings cannot crowd
        // out applicable examples. Closing the lazy stream also closes its JDBC result set.
        try (Stream<SavedReview> reviews = jdbc.queryForStream(sql,
                (rs, row) -> new SavedReview(rs.getLong("id"), rs.getString("category"),
                        rs.getString("input_json"), rs.getString("review_json")), arguments.toArray())) {
            return reviews.flatMap(review -> examples(review, scopes).stream()).limit(MAX_EXAMPLES).toList();
        }
    }

    private List<AgentFeedbackExample> examples(SavedReview saved, Map<Long, Scope> scopes) {
        try {
            Item item = json.readValue(saved.input(), Item.class);
            JsonNode review = json.readTree(saved.review());
            if (item == null || item.articles() == null || item.articles().stream().anyMatch(Objects::isNull)) return List.of();
            var event = item.event();
            if (event == null || event.unavailableReason() != null || event.sources() == null
                    || event.sources().isEmpty() || event.sources().stream().anyMatch(source ->
                    source == null || source.article() == null || !source.contextComplete()
                            || source.collectionTopics() == null || source.collectionTopics().isEmpty()
                            || source.collectionTopics().stream().anyMatch(Objects::isNull))
                    || !"CONFIRMED_ERROR".equals(review.path("verdict").asString())
                    || !review.path("meta").path("mock").isBoolean()
                    || review.path("meta").path("mock").asBoolean(true)
                    || !review.path("meta").path("truncated").isBoolean()
                    || review.path("meta").path("truncated").asBoolean(true)
                    || !Set.of("openai", "mindlogic-claude", "gemini").contains(review.path("meta").path("provider").asString(""))
                    || !text(review.path("diagnosis").asString(), 2000)
                    || !text(event.title(), 1000) || !text(event.summary(), 5000)) return List.of();
            Map<Long, Article> articles = new LinkedHashMap<>();
            item.articles().forEach(article -> articles.put(article.id(), article));
            var citations = review.path("evidence");
            if (!citations.isArray() || citations.isEmpty() || citations.size() > 10) return List.of();
            List<AgentFeedbackExample.Evidence> evidence = new ArrayList<>();
            for (var citation : citations) {
                long articleId = citation.path("articleId").asLong();
                String quote = citation.path("quote").asString();
                var article = articles.get(articleId);
                if (article == null || !text(quote, 300)
                        || !(contains(article.title(), quote) || contains(article.content(), quote))) return List.of();
                var restored = new AgentFeedbackExample.Evidence(articleId, quote);
                if (!evidence.contains(restored)) evidence.add(restored);
            }
            List<AgentFeedbackExample> result = new ArrayList<>();
            for (var entry : scopes.entrySet()) {
                long topicId = entry.getKey();
                var sources = event.sources().stream().filter(source -> source.collectionTopics().stream()
                        .anyMatch(topic -> Objects.equals(topic.topicId(), topicId))).toList();
                if (sources.isEmpty() || sources.stream().flatMap(source -> source.collectionTopics().stream())
                        .filter(topic -> Objects.equals(topic.topicId(), topicId))
                        .anyMatch(topic -> !entry.getValue().equals(Scope.from(topic)))) continue;
                Set<Long> articleIds = sources.stream().map(source -> source.article().id())
                        .collect(java.util.stream.Collectors.toSet());
                var scopedEvidence = evidence.stream().filter(citation -> articleIds.contains(citation.articleId())).toList();
                if (scopedEvidence.isEmpty()) continue;
                result.add(new AgentFeedbackExample(saved.id(), topicId, saved.category(), event.title(),
                        clip(event.summary(), 2000), review.path("diagnosis").asString(), scopedEvidence));
            }
            return result;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalidLegacyReview) {
            // A malformed legacy record cannot become a learning instruction or break a new run.
            return List.of();
        }
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean contains(String source, String quote) { return source != null && source.contains(quote); }
    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private static String clip(String value, int max) {
        int end = Math.min(value.length(), max);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    private record SavedReview(long id, String category, String input, String review) { }
    private record Scope(Long id, String name, String query, List<String> required,
                         List<String> optional, List<String> excluded) {
        static Scope from(CollectionTopicSnapshot value) {
            return new Scope(value.topicId(), value.topicName(), value.queryText(),
                    canonical(value.requiredKeywords()), canonical(value.optionalKeywords()), canonical(value.excludedKeywords()));
        }
        private static List<String> canonical(List<String> values) {
            return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().sorted().toList();
        }
    }
}
