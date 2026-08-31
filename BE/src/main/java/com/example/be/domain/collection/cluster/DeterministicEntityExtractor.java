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

/**
 * LLM 결과를 기다리지 않고 제목·요약·본문·주제 사전에서 보수적인 엔티티 후보만 뽑는다.
 *
 * <p><b>패턴 앵커를 제목·요약과 본문에서 다른 기준으로 뽑는다.</b> 회사 사전과 주제 키워드는
 * 닫힌 집합이라 본문까지 훑어도 안전하다. 대문자·제품코드 패턴은 열린 집합이라 위치에 따라 가른다.
 *
 * <ul>
 *   <li><b>제목·요약</b> — 기자가 사건을 가리키려고 고른 말이다. 길이·일반어·기자ID 규칙만 건다
 *   <li><b>본문</b> — 바이라인·광고·저작권 문구가 섞인다. 위 규칙에 더해
 *       <b>숫자를 포함한 토큰만</b> 받는다. {@code HBM4}·{@code DDR5}·{@code M15X}처럼
 *       제품·팹 코드는 통과하고 {@code CEO}·{@code IT}·{@code BBQ}·{@code ADVERTISEMENT}는 떨어진다
 * </ul>
 *
 * <p>본문을 통째로 버리지 않는 이유는, 제목에 없고 본문에만 나오는 제품명이 실제로 같은 사건을
 * 가리키는 신호이기 때문이다 ({@code IssueClustererTest}의 삼성전자·HBM4 케이스).
 *
 * <p>이게 왜 중요하냐면 §5-2 3단계가 <b>"엔티티 교집합 ≥ 2 AND 발행 시각 차 ≤ 48h"</b>로
 * 이슈를 묶고, {@code IssueClusterer}가 그 결과를 <b>전이적으로</b> union 하기 때문이다.
 * 아무 기사 쌍이나 {@code IT}·{@code CEO}·{@code COM} 같은 걸 2개 공유하면 전부 한 덩어리가 된다.
 * 실측(run 3859)에서 관측 303건 중 274건이 이슈 하나로 묶였다 — 이슈 #118.
 */
public final class DeterministicEntityExtractor {

    /** 기사 하나가 만들 수 있는 패턴 앵커 상한. 보일러플레이트가 많은 기사가 다리를 놓지 못하게 막는다. */
    static final int MAX_ANCHORS_PER_ARTICLE = 20;
    /** 앵커 최소 길이. 두 글자짜리는 거의 다 일반 약어(IT·AP·TV·PC)이지 사건 식별자가 아니다. */
    private static final int MIN_ANCHOR_LENGTH = 3;

    private static final Map<String, List<String>> COMPANY_ALIASES = companyAliases();
    private static final Set<String> BROAD_TOPIC_WORDS = Set.of(
            "반도체", "제조", "산업", "기술", "시장", "뉴스", "공장", "기업");
    private static final Set<String> BROAD_TECHNICAL_ANCHORS = Set.of("AI", "SK", "LG", "HD");
    /**
     * 길이 규칙을 통과하지만 사건을 가르지 못하는 일반어. 실수집(run 3859)에서 실제로 관측된 것만 넣는다.
     * 사전을 무한정 늘리는 대신 본문 제외·길이·형태 규칙이 1차 방어선이고 이건 마지막 보정이다.
     */
    private static final Set<String> GENERIC_ANCHORS = Set.of(
            "ADVERTISEMENT", "ANNIVERSARY", "COM", "NEWS", "NEWS1", "NEWSIS", "PHOTO", "VIDEO",
            "CEO", "CFO", "COO", "CTO", "CIO", "IPO", "ESG", "MOU", "GDP", "ETF",
            "THE", "AND", "FOR", "NOT", "ALL", "NEW", "ONE", "TWO", "YOU", "OUR", "WHO", "WHY",
            "HOW", "NOW", "DAY", "TOP", "BEST", "MORE", "THIS", "THAT", "WITH", "FROM", "HAVE",
            "WILL", "TIME", "YEAR", "WEEK", "CLASS", "ROOM", "WAVE", "PASS", "PLUS", "SEED",
            "PREMIUM", "EXCLUSIVE", "INTERVIEW", "REPORT", "PRESS", "TECH", "LIVE");
    private static final Pattern TECHNICAL_ANCHOR = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Z]{2,}[A-Z0-9-]*|[A-Za-z]+[0-9][A-Za-z0-9-]*)(?![A-Za-z0-9])");
    /** 기자 이메일 ID 형태. 영문 뒤에 숫자만 붙고 끝나면 사건 식별자가 아니다 (HONG1987, JUDY6956). */
    private static final Pattern REPORTER_ID = Pattern.compile("^[A-Z]+[0-9]{2,}$");

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

        // 제목·요약이 먼저다. 상한에 걸리더라도 본문 앵커보다 이쪽이 살아남아야 한다.
        int anchors = collectAnchors(
                entities, String.join("\n", nullToEmpty(title), nullToEmpty(summary)), false, 0);
        collectAnchors(entities, nullToEmpty(body), true, anchors);

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

    /**
     * 한 구간에서 앵커를 모아 {@code entities}에 넣고, 누적 앵커 수를 돌려준다.
     *
     * @param requireDigit 본문처럼 노이즈가 섞이는 구간에서 제품·팹 코드 형태만 받을지 여부
     */
    private static int collectAnchors(Set<String> entities,
                                      String source,
                                      boolean requireDigit,
                                      int alreadyCollected) {
        int anchors = alreadyCollected;
        Matcher anchor = TECHNICAL_ANCHOR.matcher(source);
        while (anchor.find() && anchors < MAX_ANCHORS_PER_ARTICLE) {
            String value = anchor.group().toUpperCase(Locale.ROOT);
            if (isUsableAnchor(value, requireDigit) && entities.add(value)) {
                anchors++;
            }
        }
        return anchors;
    }

    /** 사건을 가를 수 있는 앵커만 통과시킨다. 판정 순서는 싼 것부터다. */
    private static boolean isUsableAnchor(String value, boolean requireDigit) {
        return value.length() >= MIN_ANCHOR_LENGTH
                && !BROAD_TECHNICAL_ANCHORS.contains(value)
                && !GENERIC_ANCHORS.contains(value)
                && !REPORTER_ID.matcher(value).matches()
                && (!requireDigit || containsDigit(value));
    }

    private static boolean containsDigit(String value) {
        return value.chars().anyMatch(Character::isDigit);
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
