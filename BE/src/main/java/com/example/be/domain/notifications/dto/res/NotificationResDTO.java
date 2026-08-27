package com.example.be.domain.notifications.dto.res;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
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
    public static class PreviewChunk {
        private final int seq;
        private final int length;
        private final String body;
    }

    @Getter @Builder @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Preview {
        private final Long reportId;
        private final Long channelId;
        private final String channelType;
        private final String parseMode;
        private final int maxLength;
        private final String subject;
        private final List<PreviewChunk> chunks;
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
