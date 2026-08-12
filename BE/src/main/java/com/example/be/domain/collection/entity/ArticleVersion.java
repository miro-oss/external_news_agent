package com.example.be.domain.collection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 기사가 바뀔 때마다 남기는 이전 상태(§2-8). 현재 상태만 들고 있으면 "무엇이 언제 바뀌었는지"를 알 수 없다.
 *
 * <p>기사 수정은 뉴스에서 드물지 않다 — 제목이 바뀌거나 내용이 정정된다. M4의 UPDATED 분석이 이 이력을 근거로 삼는다.
 */
@Entity
@Table(name = "news_article_versions")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleVersion {

    public static final int FIRST_VERSION_NO = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id")
    private CollectionRun run;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "title", length = Article.MAX_TITLE_LENGTH)
    private String title;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "body")
    private String body;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    /**
     * 갱신 직전의 기사 상태를 그대로 떠서 이력으로 남긴다.
     */
    public static ArticleVersion snapshotOf(Article article, CollectionRun run, int versionNo, LocalDateTime changedAt) {
        return ArticleVersion.builder()
                .article(article)
                .run(run)
                .versionNo(versionNo)
                .title(article.getTitle())
                .body(article.getBody())
                .contentHash(article.getContentHash())
                .changedAt(changedAt)
                .build();
    }
}
