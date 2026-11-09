package com.example.be.domain.collection.scoring;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicEditorialFocus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Balances technical sources without moving any other topic's existing selection slots. */
public final class TechnicalSourceBalancer {

    private TechnicalSourceBalancer() { }

    /** A missing snapshot never makes a historical run inherit today's editorial scope. */
    public static Group group(Topic capturedTopic, Long sourceId) {
        if (capturedTopic == null || capturedTopic.getId() == null || sourceId == null
                || !TopicEditorialFocus.isAiTechnology(capturedTopic)) {
            return null;
        }
        return new Group(capturedTopic.getId(), sourceId);
    }

    /** Input is already deduplicated and sorted; a null group keeps its exact original position. */
    public static <T> List<T> balance(List<T> ordered, Function<T, Group> groupOf,
                                    Comparator<? super T> priority) {
        Map<Group, List<T>> groups = new LinkedHashMap<>();
        for (T value : ordered) {
            Group group = groupOf.apply(value);
            if (group != null) groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(value);
        }
        if (groups.size() < 2) return ordered;

        List<T> balanced = new ArrayList<>();
        for (int round = 0; ; round++) {
            int index = round;
            List<T> next = groups.values().stream().filter(values -> values.size() > index)
                    .map(values -> values.get(index)).sorted(priority).toList();
            if (next.isEmpty()) break;
            balanced.addAll(next);
        }

        var technical = balanced.iterator();
        List<T> result = new ArrayList<>(ordered.size());
        for (T value : ordered) result.add(groupOf.apply(value) == null ? value : technical.next());
        return List.copyOf(result);
    }

    public record Group(Long topicId, Long sourceId) { }
}
