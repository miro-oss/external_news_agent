package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.model.FeedbackModels.Item;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class CompanyInterestModels {
    private CompanyInterestModels() { }

    public enum Status { DRAFT, ACTIVE, REVOKED }
    public record Topic(long id, String name) { }
    public record ActiveCriterion(long id, long topicId, String topicName, String instruction, long version) { }
    public record StoredCriterion(long id, long feedbackId, long sourceReportId, String comment, String diagnosis,
                                  Item input, Long topicId, String instruction, String reason, Status status,
                                  long version, LocalDateTime createdAt, LocalDateTime updatedAt,
                                  LocalDateTime activatedAt) { }
    public record Application(long reportId, String reportScope, OffsetDateTime appliedAt,
                              int evaluatedCount, int suppressedCount) { }
    public record Criterion(long id, long feedbackId, long sourceReportId, String eventTitle, String comment,
                            String diagnosis, List<Topic> topics, Long topicId, String instruction, String reason,
                            Status status, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            OffsetDateTime activatedAt, Application lastApplication) { }
    public record Criteria(List<Criterion> criteria) { }
    public record Update(Long version, String instruction, Long topicId, String status) { }

    public static List<Topic> topics(Item input) {
        if (input == null || input.event() == null || input.event().topics() == null) return List.of();
        var topics = new java.util.LinkedHashMap<Long, Topic>();
        input.event().topics().stream().filter(java.util.Objects::nonNull)
                .forEach(topic -> topics.putIfAbsent(topic.id(), new Topic(topic.id(), topic.name())));
        return List.copyOf(topics.values());
    }
}
