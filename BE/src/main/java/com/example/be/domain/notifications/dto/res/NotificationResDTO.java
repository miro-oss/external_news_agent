package com.example.be.domain.notifications.dto.res;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class NotificationResDTO {

    private NotificationResDTO() {
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"id", "channelType", "name", "config", "maxLength", "active", "tokenConfigured"})
    public static class Channel {
        private final Long id;
        private final String channelType;
        private final String name;
        private final Map<String, Object> config;
        private final int maxLength;
        private final boolean active;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final Boolean tokenConfigured;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Destination {
        private final Long channelId;
        private final String channelType;
        private final String address;
        private final boolean use;
        private final boolean onboarded;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Recipient {
        private final Long id;
        private final String name;
        private final String phone;
        private final String email;
        private final String memo;
        private final boolean active;
        private final List<Destination> destinations;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final List<String> groupNames;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class RecipientBasic {
        private final Long id;
        private final String name;
        private final String phone;
        private final String email;
        private final String memo;
        private final boolean active;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class RecipientDeleted {
        private final Long id;
        private final boolean active;
        private final OffsetDateTime deletedAt;
        private final int removedGroupCount;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Destinations {
        private final Long recipientId;
        private final List<Destination> destinations;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class GroupMember {
        private final Long recipientId;
        private final String name;
        private final boolean active;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Group {
        private final Long id;
        private final String name;
        private final String perspective;
        private final boolean active;
        private final int memberCount;
        private final int activeMemberCount;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final List<GroupMember> members;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class GroupMembers {
        private final Long groupId;
        private final List<GroupMember> members;
        private final int addedCount;
        private final int removedCount;
        private final int memberCount;
        private final int activeMemberCount;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class GroupDeleted {
        private final Long id;
        private final OffsetDateTime deletedAt;
        private final int removedMemberCount;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(description = "채널별 미리보기 본문. 텔레그램과 이메일 모두 하나의 본문을 반환합니다.")
    public static class PreviewChunk {
        @Schema(description = "본문 순번", example = "1")
        private final int seq;
        @Schema(description = "렌더링된 body 문자열 길이")
        private final int length;
        @Schema(description = "실제 발송과 동일한 HTML 본문. 텔레그램은 뉴스 카드형, 이메일은 핵심 요약과 이벤트별 브리핑형입니다.")
        private final String body;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(description = "저장된 보고서의 채널별 발송 미리보기. AI 재호출과 발송 이력 저장 없이 렌더링합니다.")
    public static class Preview {
        private final Long reportId;
        private final Long channelId;
        private final String channelType;
        @Schema(description = "텔레그램은 HTML, 이메일은 null", nullable = true)
        private final String parseMode;
        @Schema(description = "채널의 본문 길이 제한. 텔레그램 본문은 이 길이 안으로 제한합니다.")
        private final int maxLength;
        @Schema(description = "이메일 제목. 텔레그램은 null", nullable = true)
        private final String subject;
        @Schema(description = "텔레그램은 뉴스 카드 메시지 1개, 이메일은 브리핑 HTML 본문 1개")
        private final List<PreviewChunk> chunks;
        @Schema(description = "본문 개수", example = "1")
        private final int chunkCount;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class SendResult {
        private final Long recipientId;
        private final String recipientName;
        private final String channelType;
        private final String address;
        private final String status;
        private final String externalMessageId;
        private final Integer chunkCount;
        private final OffsetDateTime sentAt;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String reason;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String message;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class SendBatch {
        private final String deliveryBatchId;
        private final Long reportId;
        private final OffsetDateTime requestedAt;
        private final int targetCount;
        private final int sentCount;
        private final int failedCount;
        private final int skippedCount;
        private final List<SendResult> results;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class DeliveryLog {
        private final Long id;
        private final String deliveryBatchId;
        private final Long reportId;
        private final Long runId;
        private final Long recipientId;
        private final String recipientName;
        private final String channelType;
        private final String address;
        private final String status;
        private final String externalMessageId;
        private final Integer chunkSeq;
        private final Integer chunkCount;
        private final String errorMessage;
        private final OffsetDateTime sentAt;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class DeliverySummary {
        private final long sentCount;
        private final long failedCount;
        private final long skippedCount;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages", "hasNext", "summary"})
    public static class DeliveryLogs {
        private final List<DeliveryLog> content;
        private final int page;
        private final int size;
        private final long totalElements;
        private final int totalPages;
        private final boolean hasNext;
        private final DeliverySummary summary;
    }
}
