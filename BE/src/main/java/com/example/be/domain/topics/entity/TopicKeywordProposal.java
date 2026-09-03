package com.example.be.domain.topics.entity;

import com.example.be.domain.topics.converter.TopicKeywordChangeListConverter;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "news_topic_keyword_proposals")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TopicKeywordProposal {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    public static final int MAX_SUMMARY_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @Column(name = "collection_run_id", nullable = false)
    private Long collectionRunId;

    @Column(name = "idempotency_key", nullable = false, length = MAX_IDEMPOTENCY_KEY_LENGTH)
    private String idempotencyKey;

    @Column(name = "summary", nullable = false, length = MAX_SUMMARY_LENGTH)
    private String summary;

    @Convert(converter = TopicKeywordChangeListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "changes_json", nullable = false)
    private List<TopicKeywordChange> changes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TopicKeywordProposalStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public boolean isPending() {
        return status == TopicKeywordProposalStatus.PENDING;
    }

    public void approve(LocalDateTime reviewedAt) {
        this.status = TopicKeywordProposalStatus.APPROVED;
        this.reviewedAt = reviewedAt;
    }

    public void reject(LocalDateTime reviewedAt) {
        this.status = TopicKeywordProposalStatus.REJECTED;
        this.reviewedAt = reviewedAt;
    }
}
