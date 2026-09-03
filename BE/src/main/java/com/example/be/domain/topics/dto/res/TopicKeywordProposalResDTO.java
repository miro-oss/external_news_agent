package com.example.be.domain.topics.dto.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

public class TopicKeywordProposalResDTO {

    private TopicKeywordProposalResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicKeywordProposalResponse", description = "수집 전략가 키워드 제안")
    public static class Item {

        @Schema(description = "키워드 제안 ID", example = "1")
        private final Long id;

        @Schema(description = "수집 주제 ID", example = "3")
        private final Long topicId;

        @Schema(description = "수집 주제명", example = "HBM")
        private final String topicName;

        @Schema(description = "제안을 만든 수집 실행 ID", example = "148")
        private final Long collectionRunId;

        @Schema(description = "검토 상태", example = "PENDING", allowableValues = {"PENDING", "APPROVED", "REJECTED"})
        private final String status;

        @Schema(description = "전략가 제안 요약", example = "HBM4와 경쟁사 확장 키워드를 추가하고 잡음 키워드를 정리합니다.")
        private final String summary;

        @Schema(description = "검토 시각. 대기 중이면 null", example = "2026-09-03T11:20:00+09:00")
        private final OffsetDateTime reviewedAt;

        @Schema(description = "제안 생성 시각", example = "2026-09-03T10:15:00+09:00")
        private final OffsetDateTime createdAt;

        @Schema(description = "현재 주제 키워드")
        private final CurrentKeywords currentKeywords;

        @Schema(description = "변경 제안 목록")
        private final List<Change> changes;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicKeywordProposalCurrentKeywords", description = "주제의 현재 키워드 상태")
    public static class CurrentKeywords {

        @Schema(description = "AND 필수 키워드")
        private final List<String> requiredKeywords;

        @Schema(description = "OR 선택 키워드")
        private final List<String> optionalKeywords;

        @Schema(description = "NOT 제외 키워드")
        private final List<String> excludedKeywords;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicKeywordProposalChange", description = "개별 키워드 변경 제안")
    public static class Change {

        @Schema(description = "대상 키워드 버킷", example = "OPTIONAL", allowableValues = {"REQUIRED", "OPTIONAL", "EXCLUDED"})
        private final String bucket;

        @Schema(description = "변경 종류", example = "ADD", allowableValues = {"ADD", "REMOVE"})
        private final String action;

        @Schema(description = "키워드", example = "HBM4")
        private final String keyword;

        @Schema(description = "제안 사유", example = "이번 주기 신규 기사 다수에서 반복 등장했습니다.")
        private final String reason;
    }
}
