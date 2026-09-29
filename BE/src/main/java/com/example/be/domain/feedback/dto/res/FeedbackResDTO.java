package com.example.be.domain.feedback.dto.res;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

public final class FeedbackResDTO {
    private FeedbackResDTO() { }

    public record Source(long articleId, String title, String url) { }
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
}
