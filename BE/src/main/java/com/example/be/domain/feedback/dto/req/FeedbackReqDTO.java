package com.example.be.domain.feedback.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;

public final class FeedbackReqDTO {
    private FeedbackReqDTO() { }

    public record TokenRequest(String token) { }
    public record SubmitRequest(String token, Long itemId,
                                @Schema(allowableValues={"PREFERENCE","TOPIC_MISMATCH","SUMMARY_ERROR","WRONG_CLUSTER","OTHER"},example="PREFERENCE") String category, String comment,
                                Boolean allowPersonalization, String idempotencyKey) { }
    public record RevokeRequest(String token, Integer version) { }
}
