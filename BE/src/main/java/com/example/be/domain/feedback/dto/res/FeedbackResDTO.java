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
    public record PublicEvent(@Schema(description="오류 이벤트를 제외하기 전 보고서 스냅샷에 연결된 이벤트 키") String eventKey,
                              @Schema(description="현재 표시하는 중요 이벤트 목록의 0부터 시작하는 인덱스") int eventIndex, String title, String summary, String significance,
                              List<Long> sourceFindingIds) { }
    public record EventFeedback(long id, String eventKey,
                                @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"}) String category,
                                String comment,
                                @Schema(allowableValues={"PENDING","PROCESSING","COMPLETED","FAILED"}) String status,
                                @Schema(allowableValues={"PREFERENCE","CONFIRMED_ERROR","NOT_CONFIRMED","INSUFFICIENT_EVIDENCE"},nullable=true) String verdict,
                                String diagnosis, OffsetDateTime createdAt) { }
    public record EventFeedbackContext(long reportId, List<PublicEvent> events, List<EventFeedback> feedback) { }
}
