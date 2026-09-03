package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FULLTEXT 본문끼리만 비교하는 64비트 SimHash. 기존 contentHash와 역할을 섞지 않는다. */
public final class SimHash {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(SimHash::newDigest);

    private SimHash() {
    }

    public static long of(String body) {
        return tryOf(body).orElseThrow(() -> new IllegalArgumentException("본문에 SimHash 토큰이 없습니다."));
    }

    public static OptionalLong tryOf(String body) {
        if (body == null || body.isBlank()) {
            return OptionalLong.empty();
        }
        String normalized = Normalizer.normalize(body, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return tryOfNormalized(normalized);
    }

    private static OptionalLong tryOfNormalized(String normalized) {
        Map<String, Integer> features = features(normalized);
        if (features.isEmpty()) {
            return OptionalLong.empty();
        }
        int[] weights = new int[Long.SIZE];
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            long hash = featureHash(entry.getKey());
            for (int bit = 0; bit < Long.SIZE; bit++) {
                weights[bit] += (hash & (1L << bit)) == 0 ? -entry.getValue() : entry.getValue();
            }
        }
        long fingerprint = 0;
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if (weights[bit] >= 0) {
                fingerprint |= 1L << bit;
            }
        }
        return OptionalLong.of(fingerprint);
    }

    /** 기사 본문 뒤에 붙은 매체 푸터를 걷어내고, 본문이 충분히 남을 때만 지문을 만든다. */
    static OptionalLong tryOfArticleBody(String body, int minArticleContentLength) {
        String articleContent = ArticleBodyCleaner.withoutTrailingBoilerplate(body);
        if (articleContent.length() < minArticleContentLength) {
            return OptionalLong.empty();
        }
        return tryOfNormalized(articleContent.toLowerCase(Locale.ROOT));
    }

    public static int distance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    public static String toHex(long value) {
        return HexFormat.of().toHexDigits(value);
    }

    public static long fromHex(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{16}")) {
            throw new IllegalArgumentException("SimHash는 16자리 16진수여야 합니다.");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static Map<String, Integer> features(String normalized) {
        Matcher matcher = WORD.matcher(normalized);
        Map<String, Integer> words = new LinkedHashMap<>();
        while (matcher.find()) {
            if (matcher.group().length() >= 2) {
                words.merge(matcher.group(), 1, Integer::sum);
            }
        }
        // 빈도를 보존한 단어 feature를 쓴다. 전재 매체가 머리말이나 문단 하나를 손대도 전체 투표에서
        // 영향이 작고, 해밍 임계 3의 보수성을 유지할 수 있다.
        return Map.copyOf(words);
    }

    private static long featureHash(String feature) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return ByteBuffer.wrap(digest.digest(feature.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }
}
