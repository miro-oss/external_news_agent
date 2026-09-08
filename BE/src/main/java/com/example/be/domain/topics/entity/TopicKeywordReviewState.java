package com.example.be.domain.topics.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Applies and reverses individual keyword changes without restoring a whole topic snapshot. */
public final class TopicKeywordReviewState {

    private final Map<TopicKeywordBucket, LinkedHashMap<String, String>> keywords =
            new EnumMap<>(TopicKeywordBucket.class);
    private final Map<String, Long> revisions;

    public TopicKeywordReviewState(List<String> required, List<String> optional, List<String> excluded,
                                   Map<String, Long> revisions) {
        keywords.put(TopicKeywordBucket.REQUIRED, keywordMap(required));
        keywords.put(TopicKeywordBucket.OPTIONAL, keywordMap(optional));
        keywords.put(TopicKeywordBucket.EXCLUDED, keywordMap(excluded));
        this.revisions = new LinkedHashMap<>(revisions == null ? Map.of() : revisions);
    }

    public List<TopicKeywordAppliedChange> apply(List<TopicKeywordChange> changes) {
        Map<Key, String> before = new LinkedHashMap<>();
        for (TopicKeywordChange change : changes) {
            Key key = new Key(change.bucket(), normalize(change.keyword()));
            Map<String, String> target = keywords.get(key.bucket());
            if (!before.containsKey(key)) before.put(key, target.get(key.keyword()));
            if (change.action() == TopicKeywordChangeAction.ADD) target.putIfAbsent(key.keyword(), change.keyword());
            else target.remove(key.keyword());
        }

        List<TopicKeywordAppliedChange> applied = new ArrayList<>();
        before.forEach((key, original) -> {
            // A later approval also owns its explicit ADD/REMOVE intent when that action is a no-op.
            long revision = advance(key);
            String after = keywords.get(key.bucket()).get(key.keyword());
            if (!Objects.equals(original, after)) {
                applied.add(new TopicKeywordAppliedChange(key.bucket(), key.keyword(), original, after, revision));
            }
        });
        return List.copyOf(applied);
    }

    public void replace(TopicKeywordBucket bucket, List<String> replacement) {
        Map<String, String> previous = keywords.get(bucket);
        LinkedHashMap<String, String> next = keywordMap(replacement);
        var keys = new LinkedHashSet<>(previous.keySet());
        keys.addAll(next.keySet());
        for (String keyword : keys) {
            if (!Objects.equals(previous.get(keyword), next.get(keyword))) advance(new Key(bucket, keyword));
        }
        keywords.put(bucket, next);
    }

    public void reverse(List<TopicKeywordAppliedChange> changes) {
        for (TopicKeywordAppliedChange change : changes) {
            Key key = new Key(change.bucket(), normalize(change.keyword()));
            Map<String, String> target = keywords.get(key.bucket());
            if (revision(key) != change.revision() || !Objects.equals(target.get(key.keyword()), change.after())) {
                continue;
            }
            if (change.before() == null) target.remove(key.keyword());
            else target.put(key.keyword(), change.before());
            advance(key);
        }
    }

    public List<String> keywords(TopicKeywordBucket bucket) {
        return List.copyOf(keywords.get(bucket).values());
    }

    public Map<String, Long> revisions() {
        return Map.copyOf(revisions);
    }

    private long advance(Key key) {
        long next = revision(key) + 1;
        revisions.put(key.persistedKey(), next);
        return next;
    }

    private long revision(Key key) {
        return revisions.getOrDefault(key.persistedKey(), 0L);
    }

    private static LinkedHashMap<String, String> keywordMap(List<String> values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.putIfAbsent(normalize(value), value.trim());
            }
        }
        return result;
    }

    public static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record Key(TopicKeywordBucket bucket, String keyword) {
        String persistedKey() {
            return bucket.name() + ":" + keyword;
        }
    }
}
