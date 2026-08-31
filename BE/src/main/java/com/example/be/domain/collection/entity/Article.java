package com.example.be.domain.collection.entity;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.issues.entity.ContentGroup;
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
import java.util.Objects;

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

    /** URL이 다른 전재 기사도 행은 보존하고 같은 본문 중복군만 가리킨다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_group_id")
    private ContentGroup contentGroup;

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
     *
     * <p>둘 다 없으면 "바뀐 게 없다"로 본다. 본문을 못 받은 METADATA_ONLY 기사를 매 실행마다 UPDATED로 찍으면
     * 실행 통계가 부풀고 바뀌지도 않은 버전이 계속 쌓인다. 본문이 없을 때 무엇으로 해시를 만들지는
     * 수집 엔진이 정한다(제목·요약 지문) — 여기서는 비교만 한다.
     */
    public boolean hasSameContent(String contentHash) {
        return Objects.equals(this.contentHash, contentHash);
    }

    /**
     * 내용이 그대로일 때. 언제 마지막으로 봤는지만 남긴다.
     */
    public void markSeen(CollectionRun run) {
        this.lastSeenRun = run;
    }

    /**
     * 전문 추출 결과만 반영한다.
     *
     * <p><b>{@code contentHash}를 건드리지 않는다.</b> 변경 판정은 매 실행 피드가 주는 제목+요약 지문으로 한다.
     * 여기서 본문 해시로 덮으면, 다음 실행이 메타데이터 지문과 비교하게 되어 <b>모든 기사가 매번 UPDATED</b>가 된다.
     * 본문은 별도 수집 단계에서 갱신되므로 실행 간 메타데이터 변경 비교의 기준이 될 수 없다.
     */
    public void applyFullText(String body, FetchStatus fetchStatus, LocalDateTime updatedAt) {
        // 재수집 실패나 차단이 직전 전문까지 지우지 않게 성공한 응답만 본문을 교체한다.
        if (fetchStatus == FetchStatus.FULLTEXT) {
            this.body = body;
        }
        this.fetchStatus = fetchStatus;
        this.updatedAt = updatedAt;
    }

    public void assignContentGroup(ContentGroup contentGroup) {
        this.contentGroup = contentGroup;
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
