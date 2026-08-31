package com.example.be.domain.issues.entity;

import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_watches", uniqueConstraints =
        @UniqueConstraint(name = "uq_watch_issue_type", columnNames = {"issue_id", "watch_type"}))
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsWatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_type", nullable = false, length = 30)
    private WatchType watchType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private NewsIssue issue;

    @Column(name = "sensitivity_at_watch", precision = 6, scale = 2)
    private BigDecimal sensitivityAtWatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notify_group_id")
    private NotificationGroup notifyGroup;

    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    public void claimUntil(LocalDateTime cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public void renewUntil(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        this.cooldownUntil = null;
        this.active = true;
    }

    public void moveToIssue(NewsIssue issue) {
        this.issue = issue;
    }

    public void deactivate() {
        this.active = false;
    }
}
