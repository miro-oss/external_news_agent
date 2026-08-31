package com.example.be.domain.notifications.entity;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.NewsWatch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "watch_notification_outbox")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchAlertOutbox {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "watch_id", nullable = false)
    private NewsWatch watch;

    @Column(name = "notify_group_id")
    private Long notifyGroupId;

    @Column(name = "issue_title", nullable = false, length = Article.MAX_TITLE_LENGTH)
    private String issueTitle;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "follow_up_count", nullable = false)
    private int followUpCount;

    @Column(name = "publisher_count", nullable = false)
    private int publisherCount;

    @Column(name = "queued_at", nullable = false)
    private OffsetDateTime queuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WatchAlertDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    public void startProcessing(LocalDateTime startedAt) {
        this.status = WatchAlertDeliveryStatus.PROCESSING;
        this.attemptCount++;
        this.processingStartedAt = startedAt;
        this.lastError = null;
    }

    public void markSent(LocalDateTime deliveredAt) {
        this.status = WatchAlertDeliveryStatus.SENT;
        this.deliveredAt = deliveredAt;
        this.processingStartedAt = null;
        this.lastError = null;
    }

    public void retry(String error) {
        this.status = WatchAlertDeliveryStatus.PENDING;
        this.processingStartedAt = null;
        this.lastError = truncate(error);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
