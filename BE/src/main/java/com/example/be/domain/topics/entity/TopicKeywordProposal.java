package com.example.be.domain.topics.entity;

import com.example.be.domain.topics.converter.TopicKeywordChangeListConverter;
import com.example.be.global.converter.StringListJsonConverter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "baseline_required_keywords", nullable = false)
    private List<String> baselineRequiredKeywords = List.of();

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "baseline_optional_keywords", nullable = false)
    private List<String> baselineOptionalKeywords = List.of();

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "baseline_excluded_keywords", nullable = false)
    private List<String> baselineExcludedKeywords = List.of();

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

    public boolean matchesCurrentTopicKeywords() {
        return normalized(baselineRequiredKeywords).equals(normalized(topic.getRequiredKeywords()))
                && normalized(baselineOptionalKeywords).equals(normalized(topic.getOptionalKeywords()))
                && normalized(baselineExcludedKeywords).equals(normalized(topic.getExcludedKeywords()));
    }

    public void approve(LocalDateTime reviewedAt) {
        this.status = TopicKeywordProposalStatus.APPROVED;
        this.reviewedAt = reviewedAt;
    }

    public void reject(LocalDateTime reviewedAt) {
        this.status = TopicKeywordProposalStatus.REJECTED;
        this.reviewedAt = reviewedAt;
    }

    private static Set<String> normalized(List<String> keywords) {
        Set<String> result = new HashSet<>();
        if (keywords == null) {
            return result;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()) {
                result.add(keyword.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }
}
