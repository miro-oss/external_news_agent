package com.example.be.domain.collection.entity;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 수집된 기사 1건.
 *
 * <p>{@code urlHash}가 중복 판정의 유일한 기준이다. 검색 결과 URL은 매우 길어 Oracle 인덱스 키 길이 제한에
 * 걸리므로 SHA-256으로 줄여서 건다(§2-8). 같은 기사가 여러 주제에 걸려도 한 건만 남는다.
 */
@Entity
@Table(name = "news_articles")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Article {

    public static final int URL_HASH_LENGTH = 64;
    public static final int MAX_CANONICAL_URL_LENGTH = 2000;
    public static final int MAX_TITLE_LENGTH = 1000;
    public static final int MAX_SOURCE_NAME_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "url_hash", nullable = false, length = URL_HASH_LENGTH)
    private String urlHash;

    @Column(name = "canonical_url", nullable = false, length = MAX_CANONICAL_URL_LENGTH)
    private String canonicalUrl;

    @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "summary")
    private String summary;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "body")
    private String body;

    /** 본문이 실제로 바뀌었는지 판정한다. 같으면 SKIPPED, 다르면 UPDATED + 버전 1건. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "language", length = 5)
    private String language;

    @Column(name = "source_name", length = MAX_SOURCE_NAME_LENGTH)
    private String sourceName;

    /** 해외 기사는 오프셋이 의미를 갖는다. 서버가 만드는 시각과 달리 오프셋을 보존한다. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "fetch_status", nullable = false, length = 30)
    private FetchStatus fetchStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "first_seen_run_id")
    private CollectionRun firstSeenRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_seen_run_id")
    private CollectionRun lastSeenRun;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 이미 있던 기사를 다시 만났을 때 내용이 바뀌었는지 본다. 발행일이나 요약이 조금 달라지는 일은 흔해서,
     * 본문 해시가 같으면 갱신으로 치지 않는다.
     */
    public boolean hasSameContent(String contentHash) {
        return this.contentHash != null && this.contentHash.equals(contentHash);
    }

    /**
     * 내용이 그대로일 때. 언제 마지막으로 봤는지만 남긴다.
     */
    public void markSeen(CollectionRun run) {
        this.lastSeenRun = run;
    }

    public void applyUpdate(String title,
                            String summary,
                            String body,
                            String contentHash,
                            FetchStatus fetchStatus,
                            CollectionRun run,
                            LocalDateTime updatedAt) {
        this.title = title;
        this.summary = summary;
        this.body = body;
        this.contentHash = contentHash;
        this.fetchStatus = fetchStatus;
        this.lastSeenRun = run;
        this.updatedAt = updatedAt;
    }
}
