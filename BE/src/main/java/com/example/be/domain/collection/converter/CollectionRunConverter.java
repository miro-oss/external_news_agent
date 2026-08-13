package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.sources.entity.Source;
import com.example.be.global.config.ApiTimeZone;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

public class CollectionRunConverter {

    private CollectionRunConverter() {
    }

    public static CollectionRunResDTO.Created toCreated(CollectionRun run,
                                                        List<Long> targetTopicIds,
                                                        int targetCombinationCount) {
        return CollectionRunResDTO.Created.builder()
                .runId(run.getId())
                .status(run.getStatus().name())
                .triggerType(run.getTriggerType().name())
                .idempotencyKey(run.getIdempotencyKey())
                .targetTopicIds(targetTopicIds)
                .targetCombinationCount(targetCombinationCount)
                .startedAt(toOffset(run.getStartedAt()))
                .build();
    }

    public static CollectionRunResDTO.Created toAlreadyRunning(CollectionRun run) {
        return CollectionRunResDTO.Created.builder()
                .runId(run.getId())
                .status(run.getStatus().name())
                .triggerType(run.getTriggerType().name())
                .idempotencyKey(run.getIdempotencyKey())
                .startedAt(toOffset(run.getStartedAt()))
                .build();
    }

    public static CollectionRunResDTO.Summary toSummary(CollectionRun run, int warningCount) {
        return CollectionRunResDTO.Summary.builder()
                .runId(run.getId())
                .status(run.getStatus().name())
                .triggerType(run.getTriggerType().name())
                .startedAt(toOffset(run.getStartedAt()))
                .finishedAt(toOffset(run.getFinishedAt()))
                .scannedCount(run.getScannedCount())
                .newCount(run.getNewCount())
                .updatedCount(run.getUpdatedCount())
                .skippedCount(run.getSkippedCount())
                .warningCount(warningCount)
                .reportId(run.getReportId())
                .build();
    }

    public static CollectionRunResDTO.Detail toDetail(CollectionRun run,
                                                      List<CollectionRunItem> items,
                                                      List<CollectionRunWarning> warnings) {
        return CollectionRunResDTO.Detail.builder()
                .runId(run.getId())
                .status(run.getStatus().name())
                .triggerType(run.getTriggerType().name())
                .idempotencyKey(run.getIdempotencyKey())
                .startedAt(toOffset(run.getStartedAt()))
                .finishedAt(toOffset(run.getFinishedAt()))
                .scannedCount(run.getScannedCount())
                .newCount(run.getNewCount())
                .updatedCount(run.getUpdatedCount())
                .skippedCount(run.getSkippedCount())
                .reportId(run.getReportId())
                .breakdown(toBreakdown(items))
                .warnings(toWarnings(warnings))
                .build();
    }

    private static List<CollectionRunResDTO.Breakdown> toBreakdown(List<CollectionRunItem> items) {
        return items.stream()
                .map(item -> CollectionRunResDTO.Breakdown.builder()
                        .topicId(item.getTopic().getId())
                        .topicName(item.getTopic().getName())
                        .sourceId(item.getSource().getId())
                        .sourceName(item.getSource().getName())
                        .scannedCount(item.getScannedCount())
                        .newCount(item.getNewCount())
                        .updatedCount(item.getUpdatedCount())
                        .status(item.getStatus().name())
                        .build())
                .toList();
    }

    private static List<CollectionRunResDTO.Warning> toWarnings(List<CollectionRunWarning> warnings) {
        return warnings.stream()
                .sorted(Comparator.comparing(CollectionRunWarning::getOccurredAt)
                        .thenComparing(CollectionRunWarning::getId, Comparator.nullsLast(Long::compareTo)))
                .map(warning -> {
                    Source source = warning.getSource();
                    return CollectionRunResDTO.Warning.builder()
                            .sourceId(source == null ? null : source.getId())
                            .sourceName(source == null ? null : source.getName())
                            .code(warning.getCode())
                            .message(warning.getMessage())
                            .articleCount(warning.getArticleCount())
                            .occurredAt(toOffset(warning.getOccurredAt()))
                            .build();
                })
                .toList();
    }

    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }
}
