package com.example.be.domain.collection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 동일한 전문을 기사와 수정 이력이 함께 참조하는 불변 저장 값. */
@Entity
@Table(name = "news_article_bodies")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleBody {

    public static final int BODY_HASH_LENGTH = 64;

    @Id
    @Column(name = "body_hash", nullable = false, length = BODY_HASH_LENGTH, updatable = false)
    private String bodyHash;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    private ArticleBody(String bodyHash, String body) {
        this.bodyHash = bodyHash;
        this.body = body;
    }

    /**
     * 공백과 줄바꿈까지 원문 그대로 해시한다. 저장 여부를 보장하지 않는 값 생성 메서드이므로
     * 영속 기사에 연결할 때는 ArticleBodyStorage.intern을 통해 기존 본문을 확인한다.
     */
    public static ArticleBody of(String body) {
        if (body == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return new ArticleBody(HexFormat.of().formatHex(digest), body);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }
}
