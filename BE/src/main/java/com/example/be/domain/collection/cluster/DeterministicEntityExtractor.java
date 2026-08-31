package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM 결과를 기다리지 않고 제목·요약·본문·주제 사전에서 보수적인 엔티티 후보만 뽑는다. */
public final class DeterministicEntityExtractor {

    private static final Map<String, List<String>> COMPANY_ALIASES = companyAliases();
    private static final Set<String> BROAD_TOPIC_WORDS = Set.of(
            "반도체", "제조", "산업", "기술", "시장", "뉴스", "공장", "기업");
    private static final Set<String> BROAD_TECHNICAL_ANCHORS = Set.of("AI", "SK", "LG", "HD");
    private static final Pattern TECHNICAL_ANCHOR = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Z]{2,}[A-Z0-9-]*|[A-Za-z]+[0-9][A-Za-z0-9-]*)(?![A-Za-z0-9])");

    public Set<String> extract(String title,
                               String summary,
                               String body,
                               List<String> topicKeywords) {
        String text = String.join("\n", nullToEmpty(title), nullToEmpty(summary), nullToEmpty(body));
        String normalized = normalize(text);
        Set<String> entities = new LinkedHashSet<>();

        COMPANY_ALIASES.forEach((canonical, aliases) -> {
            if (aliases.stream().map(DeterministicEntityExtractor::normalize).anyMatch(normalized::contains)) {
                entities.add(canonical);
            }
        });

        Matcher anchor = TECHNICAL_ANCHOR.matcher(text);
        while (anchor.find()) {
            String value = anchor.group().toUpperCase(Locale.ROOT);
            if (!BROAD_TECHNICAL_ANCHORS.contains(value)) {
                entities.add(value);
            }
        }

        if (topicKeywords != null) {
            topicKeywords.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .filter(value -> value.length() >= 2)
                    .filter(value -> !BROAD_TOPIC_WORDS.contains(normalize(value)))
                    .filter(value -> normalized.contains(normalize(value)))
                    .map(DeterministicEntityExtractor::canonicalKeyword)
                    .forEach(entities::add);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(entities));
    }

    private static Map<String, List<String>> companyAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("삼성전자", List.of("삼성전자", "samsung electronics"));
        aliases.put("SK하이닉스", List.of("sk하이닉스", "sk hynix"));
        aliases.put("TSMC", List.of("tsmc", "대만반도체"));
        aliases.put("엔비디아", List.of("엔비디아", "nvidia"));
        aliases.put("AMD", List.of("amd"));
        aliases.put("인텔", List.of("인텔", "intel"));
        aliases.put("마이크론", List.of("마이크론", "micron"));
        aliases.put("브로드컴", List.of("브로드컴", "broadcom"));
        aliases.put("퀄컴", List.of("퀄컴", "qualcomm"));
        aliases.put("ASML", List.of("asml"));
        aliases.put("도쿄일렉트론", List.of("도쿄일렉트론", "tokyo electron"));
        aliases.put("애플", List.of("애플", "apple"));
        aliases.put("마이크로소프트", List.of("마이크로소프트", "microsoft"));
        aliases.put("구글", List.of("구글", "google", "alphabet"));
        aliases.put("아마존", List.of("아마존", "amazon"));
        aliases.put("메타", List.of("메타", "meta platforms"));
        aliases.put("OpenAI", List.of("openai"));
        aliases.put("Anthropic", List.of("anthropic", "앤트로픽"));
        aliases.put("Arm", List.of("arm holdings", "arm홀딩스"));
        return Map.copyOf(aliases);
    }

    private static String canonicalKeyword(String value) {
        return value.chars().anyMatch(Character::isUpperCase)
                ? value
                : normalize(value);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
