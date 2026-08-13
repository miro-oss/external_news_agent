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

import java.time.LocalDateTime;

/**
 * "이 실행에서, 이 조합으로, 이 기사를 봤다"는 관측 1건.
 *
 * <p>기사 행은 url_hash로 전역 1건이라 이 테이블 없이는 실행별 목록을 복원할 수 없다.
 * {@code lastSeenRunId}는 다음 실행에 덮이고, 같은 URL이 다른 주제에서 발견되면 첫 행의 주제만 남는다.
 * Notion 기사 목록 명세의 {@code runId}/{@code topicId}/{@code sourceId} 필터와
 * 행마다 붙는 {@code changeType}이 전부 여기서 나온다.
 */
@Entity
@Table(name = "news_collection_run_articles")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRunArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    public static CollectionRunArticle observe(CollectionRun run,
                                               Article article,
                                               Topic topic,
                                               Source source,
                                               ChangeType changeType,
                                               LocalDateTime observedAt) {
        return CollectionRunArticle.builder()
                .run(run)
                .article(article)
                .topic(topic)
                .source(source)
                .changeType(changeType)
                .observedAt(observedAt)
                .build();
    }
}
