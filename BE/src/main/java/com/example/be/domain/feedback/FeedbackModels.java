package com.example.be.domain.feedback;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;

/** Detached feedback records: a review never re-reads a mutable article as historical evidence. */
public final class FeedbackModels {
    private FeedbackModels() { }
    public enum Category { PREFERENCE, TOPIC_MISMATCH, SUMMARY_ERROR, WRONG_CLUSTER, OTHER }
    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }
    public record Topic(long id, String name, List<String> keywords, List<String> negativeKeywords) { }
    public record Source(long articleId, String title, String url) { }
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
    public record TokenRequest(String token) { }
    public record SubmitRequest(String token, Long itemId,
                                @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"},example="PREFERENCE") String category, String comment,
                                Boolean allowPersonalization, String idempotencyKey) { }
    public record RevokeRequest(String token, Integer version) { }
    public record PublicItem(long itemId, long topicId, String topicName, String title, String summary,
                             List<Source> sources) { }
    public record PublicFeedback(long id, long itemId,
                                 @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"}) String category, String comment,
                                 @Schema(allowableValues={"PENDING","PROCESSING","COMPLETED","FAILED"}) String status,
                                 @Schema(allowableValues={"PREFERENCE","CONFIRMED_ERROR","NOT_CONFIRMED","INSUFFICIENT_EVIDENCE"},nullable=true) String verdict, String diagnosis, OffsetDateTime createdAt) { }
    public record PublicPolicy(long id, long topicId, String topicName, String instruction, int version,
                               @Schema(allowableValues={"ACTIVE","REVOKED"}) String status, OffsetDateTime createdAt) { }
    public record Context(long reportId, String reportTitle, OffsetDateTime expiresAt, List<PublicItem> items,
                          List<PublicFeedback> feedback, List<PublicPolicy> policies) { }
    public record DeliveryFeedback(Set<Long> suppressedFindingIds, String token) { }
}
