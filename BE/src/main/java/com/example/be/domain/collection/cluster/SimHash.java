package com.example.be.domain.collection.cluster;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FULLTEXT 본문끼리만 비교하는 64비트 SimHash. 기존 contentHash와 역할을 섞지 않는다. */
public final class SimHash {

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9가-힣]+");

    private SimHash() {
    }

    public static long of(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("본문 없이 SimHash를 만들 수 없습니다.");
        }
        int[] weights = new int[Long.SIZE];
        for (String feature : features(body)) {
            long hash = featureHash(feature);
            for (int bit = 0; bit < Long.SIZE; bit++) {
                weights[bit] += (hash & (1L << bit)) == 0 ? -1 : 1;
            }
        }
        long fingerprint = 0;
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if (weights[bit] >= 0) {
                fingerprint |= 1L << bit;
            }
        }
        return fingerprint;
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

    private static List<String> features(String body) {
        String normalized = Normalizer.normalize(body, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Matcher matcher = WORD.matcher(normalized);
        List<String> words = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group().length() >= 2) {
                words.add(matcher.group());
            }
        }
        if (words.isEmpty()) {
            throw new IllegalArgumentException("본문에 SimHash 토큰이 없습니다.");
        }

        // 빈도를 보존한 단어 feature를 쓴다. 전재 매체가 머리말이나 문단 하나를 손대도 전체 투표에서
        // 영향이 작고, 해밍 임계 3의 보수성을 유지할 수 있다.
        return List.copyOf(words);
    }

    private static long featureHash(String feature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(feature.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }
}
