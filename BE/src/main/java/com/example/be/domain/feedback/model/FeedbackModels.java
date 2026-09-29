package com.example.be.domain.feedback.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Detached feedback records: a review never re-reads a mutable article as historical evidence. */
public final class FeedbackModels {
    private FeedbackModels() { }
    public enum Category { PREFERENCE, TOPIC_MISMATCH, SUMMARY_ERROR, WRONG_CLUSTER, OTHER }
    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }
    public record Topic(long id, String name, List<String> keywords, List<String> negativeKeywords) { }
    public record Article(long id, String title, String content, String url) { }
    public record Issue(long id, String title, String summary) { }
    public record Item(long itemId, Topic topic, Issue issue, List<Article> articles,
                       String analysisInputHash, String promptVersion, String model,
                       LocalDateTime runStartedAt, String reportPromptVersion, String reportModel,
                       String originalAnalysisSummary) {
        public Item(long itemId,Topic topic,Issue issue,List<Article> articles,String analysisInputHash,
                    String promptVersion,String model,LocalDateTime runStartedAt,String reportPromptVersion,String reportModel) {
            this(itemId,topic,issue,articles,analysisInputHash,promptVersion,model,runStartedAt,reportPromptVersion,reportModel,issue.summary());
        }
    }
    public record Snapshot(long reportId, String reportTitle, LocalDateTime generatedAt, List<Item> items, List<Long> topicIds) {
        public Snapshot { items = List.copyOf(items); topicIds=List.copyOf(topicIds); }
        public Snapshot(long reportId,String title,LocalDateTime generatedAt,List<Item> items) {
            this(reportId,title,generatedAt,items,items.stream().map(i->i.topic().id()).distinct().toList());
        }
    }
    public record Capability(long id, long recipientId, Snapshot snapshot, LocalDateTime expiresAt) { }
    public record Feedback(long id, long capabilityId, long recipientId, long reportId, long itemId,
                           Category category, String comment, boolean allowPersonalization,
                           String requestHash, Status status, String verdict, String diagnosis,
                           LocalDateTime createdAt, Item input) { }
    public record Policy(long id, long recipientId, long topicId, String topicName, String instruction,
                         int version, String status, LocalDateTime createdAt) { }
    public record Job(long id, String kind, Long feedbackId, long recipientId, long reportId,
                      long topicId, String inputJson, String resultJson, String status,
                      String claimKey, int attempts) { }
    public record EvaluationInput(Topic topic, List<Item> items, List<Policy> policies) { }
    public record DeliveryFeedback(Set<Long> suppressedFindingIds, String token) { }
}
