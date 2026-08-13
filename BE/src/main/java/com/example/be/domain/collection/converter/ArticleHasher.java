package com.example.be.domain.collection.converter;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 중복 판정과 변경 판정에 쓰는 해시.
 *
 * <p>URL을 그대로 유니크 키로 걸 수 없어서 줄인다 — 검색 결과 URL은 수백 자가 되기도 하고,
 * Oracle 인덱스 키에는 길이 제한이 있다(§2-8).
 */
public final class ArticleHasher {

    private static final String ALGORITHM = "SHA-256";

    private ArticleHasher() {
    }

    public static String urlHash(String canonicalUrl) {
        if (!StringUtils.hasText(canonicalUrl)) {
            throw new IllegalArgumentException("canonicalUrl 없이 해시를 만들 수 없다.");
        }

        return sha256(canonicalUrl.trim());
    }

    /**
     * 내용이 바뀌었는지 판정할 지문.
     *
     * <p>본문이 있으면 본문으로 잡는다. 요약이나 발행일은 매체가 사소하게 손대는 일이 잦아
     * 그걸로 판정하면 바뀌지도 않은 기사가 매번 UPDATED가 된다.
     *
     * <p>본문이 없는 단계(METADATA_ONLY)에서는 <b>제목과 요약으로 지문을 만든다.</b> null을 두면
     * 비교할 게 없어져 같은 기사를 실행마다 새로 바뀐 것으로 보게 된다.
     */
    public static String contentHash(String title, String summary, String body) {
        if (StringUtils.hasText(body)) {
            return sha256(body.strip());
        }

        return sha256(nullToEmpty(title).strip() + "" + nullToEmpty(summary).strip());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 반드시 제공한다. 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", e);
        }
    }
}
