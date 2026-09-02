package com.example.be.domain.analysis.agent.investigation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

public final class InvestigationQueryNormalizer {

    private static final Set<String> KOREAN_PARTICLES = Set.of(
            "은", "는", "이", "가", "을", "를", "의", "에", "에서", "와", "과", "도", "로", "으로"
    );

    private InvestigationQueryNormalizer() {
    }

    public static String hash(String query) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(query).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return java.util.Arrays.stream(Normalizer.normalize(query, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .trim()
                        .split("\\s+"))
                .filter(token -> !token.isBlank())
                .map(InvestigationQueryNormalizer::stripParticle)
                .filter(token -> !token.isBlank())
                .sorted()
                .distinct()
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String stripParticle(String token) {
        return KOREAN_PARTICLES.stream()
                .filter(particle -> token.length() > particle.length() && token.endsWith(particle))
                .max(java.util.Comparator.comparingInt(String::length))
                .map(particle -> token.substring(0, token.length() - particle.length()))
                .orElse(token);
    }
}
