package com.example.be.domain.feedback.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;

public final class FeedbackReqDTO {
    private FeedbackReqDTO() { }

    public record TokenRequest(String token) { }
    public record SubmitRequest(String token, Long itemId,
                                @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"},example="PREFERENCE") String category, String comment,
                                Boolean allowPersonalization, String idempotencyKey) { }
    public record RevokeRequest(String token, Integer version) { }
    public record EventSubmitRequest(
            @Schema(description="조회 응답의 이벤트 식별자",pattern="^[a-f0-9]{64}$") String eventKey,
            @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"},example="SUMMARY_ERROR") String category,
            @Schema(minLength=1,maxLength=2000,example="예정인데 이미 완료됐다고 적혀 있어요.") String comment,
            @Schema(minLength=1,maxLength=100) String idempotencyKey) { }
}
