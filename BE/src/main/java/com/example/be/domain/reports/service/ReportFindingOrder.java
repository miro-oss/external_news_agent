package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.topics.entity.TopicEditorialFocus;

import java.util.Comparator;
import java.util.List;

/** 보고서 본문과 상세 finding 카드가 공유하는 우선순위 정렬이다. */
public final class ReportFindingOrder {

    private static final Comparator<Finding> PRIORITY = Comparator
            .comparing((Finding finding) -> finding.getSensitivity().getScore()).reversed()
            .thenComparing(Finding::getRelevance)
            .thenComparing(Finding::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<Finding> TECHNOLOGY_PRIORITY = Comparator
            .comparing(Finding::getRelevance, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PRIORITY);

    private ReportFindingOrder() {
    }

    public static List<Finding> sort(List<Finding> findings) {
        return findings.stream().sorted(PRIORITY).toList();
    }

    /** Only new RUN generation uses its captured scope; reading saved reports never changes order. */
    public static List<Finding> sortForGeneration(List<Finding> findings, CollectionRun run) {
        boolean technologyReport = run != null && run.getItems() != null && !run.getItems().isEmpty()
                && run.getItems().stream().allMatch(item -> item.getTopicSnapshot() != null
                    && TopicEditorialFocus.isAiTechnology(item.getTopicSnapshot().topicName(),
                            item.getTopicSnapshot().queryText()));
        return findings.stream().sorted(technologyReport ? TECHNOLOGY_PRIORITY : PRIORITY).toList();
    }
}
