package com.example.be.domain.issues.entity;

import com.example.be.domain.collection.entity.Article;
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

import java.time.LocalDateTime;

@Entity
@Table(name = "news_content_groups")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentGroup {

    public static final int SIMHASH_HEX_LENGTH = 16;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "representative_article_id", nullable = false)
    private Article representativeArticle;

    @Column(name = "simhash", nullable = false, length = SIMHASH_HEX_LENGTH)
    private String simhash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void refreshRepresentative(Article representativeArticle, String simhash) {
        this.representativeArticle = representativeArticle;
        this.simhash = simhash;
    }
}
