package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** agent {@code evidence.py::_tokens}와 같은 토큰 계약을 Java 클러스터링에서 구현한다. */
public final class TitleTokenizer {

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9가-힣]+");
    private static final Pattern PREFIX = Pattern.compile("^\\s*\\[[^]]+]\\s*");
    private static final Pattern PUBLISHER_SUFFIX = Pattern.compile(
            "\\s*[-|｜·:]\\s*(?:뉴스|신문|일보|방송|경제|전자신문|연합뉴스|Reuters|Bloomberg)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "그리고", "그러나", "대한", "위한", "있다", "없다", "한다", "된다", "것이다",
            "the", "and", "for", "from", "that", "this", "with");
    private static final String[] KOREAN_SUFFIXES = {
            "으로", "에서", "에게", "까지", "부터", "보다", "이라고", "라는",
            "은", "는", "이", "가", "을", "를", "의", "에", "로", "과", "와", "도", "만"
    };

    private TitleTokenizer() {
    }

    public static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = normalizeTitle(value);
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            String token = stripKoreanSuffix(matcher.group());
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return Set.copyOf(tokens);
    }

    public static String normalizeTitle(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String withoutPrefixes = PREFIX.matcher(normalized).replaceAll("");
        return PUBLISHER_SUFFIX.matcher(withoutPrefixes).replaceAll("").trim();
    }

    private static String stripKoreanSuffix(String token) {
        for (String suffix : KOREAN_SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
