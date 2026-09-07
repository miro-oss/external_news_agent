package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.util.Collection;
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
 *   <li><b>제목·요약</b> — 기자가 사건을 가리키려고 고른 말이다. 길이·일반어·이메일 규칙만 건다
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
    private static final Pattern STOCK_HEADLINE_SUBJECT = Pattern.compile(
            "^([a-z0-9가-힣&.-]{2,40})\\s+주가\\s*[,，:：]");
    private static final Set<String> GENERAL_STOCK_SUBJECTS = Set.of(
            "오늘", "내일", "어제", "현재", "전체", "평균", "국내", "해외", "한국", "미국",
            "중국", "일본", "반도체", "기업", "회사", "관련", "종목", "주요", "코스피", "코스닥");

    /** 기사 하나가 만들 수 있는 패턴 앵커 상한. 보일러플레이트가 많은 기사가 다리를 놓지 못하게 막는다. */
    static final int MAX_ANCHORS_PER_ARTICLE = 20;
    /** 앵커 최소 길이. 두 글자짜리는 거의 다 일반 약어(IT·AP·TV·PC)이지 사건 식별자가 아니다. */
    private static final int MIN_ANCHOR_LENGTH = 3;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Map<String, List<String>> ORGANIZATION_ALIASES = organizationAliases();
    /**
     * 충돌 판정 전용이다. 이 별칭만으로 긍정 병합 근거를 추가하지 않는다.
     * 긍정 병합에도 쓸 별칭은 ORGANIZATION_ALIASES, 충돌에만 쓸 별칭은 이 맵에 추가한다.
     * vendorConflictAliasesDoNotCreateNewPositiveOrganizationEdges 테스트가 이 구분을 검증한다.
     */
    private static final Map<String, List<String>> TITLE_CONFLICT_ALIASES = Map.of(
            "한화세미텍", List.of("한화세미텍", "hanwha semitech"),
            "디엠에스", List.of("디엠에스", "dms"),
            "한미반도체", List.of("한미반도체", "hanmi semiconductor"));
    private static final List<String> KOREAN_POSTPOSITIONS = List.of(
            "으로부터", "에게서", "이라도", "에서", "에게", "으로", "처럼", "보다", "까지", "부터",
            "은", "는", "이", "가", "을", "를", "과", "와", "의", "도", "만", "에", "로");
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

    public Set<String> extract(String title,
                               String summary,
                               String body,
                               List<String> topicKeywords) {
        return extractWithOrganizations(title, summary, body, topicKeywords).entities();
    }

    /** LLM 산문은 조직 사전·제품 코드·이슈 고유어로 제한하고 일반 영문 약어를 제외한다. */
    public Set<String> extractProse(String text, Collection<String> knownEntities) {
        String source = nullToEmpty(text);
        String normalized = normalize(source);
        Set<String> entities = new LinkedHashSet<>();
        collectOrganizations(normalized, entities);
        collectAnchors(entities, source, true, 0);
        if (knownEntities != null) {
            knownEntities.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .filter(value -> !normalize(value).matches("[a-z-]+"))
                    .filter(value -> containsAlias(normalized, normalize(value)))
                    .map(DeterministicEntityExtractor::canonicalEntity)
                    .forEach(entities::add);
        }
        return Collections.unmodifiableSet(entities);
    }

    /** LLM이 반환한 조직 별칭도 추출 결과와 같은 정규명으로 맞춘다. */
    public Set<String> canonicalizeEntities(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> canonical = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(DeterministicEntityExtractor::canonicalEntity)
                .forEach(canonical::add);
        return Collections.unmodifiableSet(canonical);
    }

    /** 조직 사전은 제목·요약과 본문을 한 번씩만 훑고, 보조 간선용 조직은 제목·요약으로 제한한다. */
    Extraction extractWithOrganizations(String title,
                                         String summary,
                                         String body,
                                         List<String> topicKeywords) {
        String headline = String.join("\n", nullToEmpty(title), nullToEmpty(summary));
        String normalizedHeadline = normalize(headline);
        String normalizedBody = normalize(nullToEmpty(body));
        Set<String> entities = new LinkedHashSet<>();
        Set<String> organizations = new LinkedHashSet<>();

        collectOrganizations(normalizedHeadline, organizations);
        entities.addAll(organizations);

        // 제목·요약이 먼저다. 상한에 걸리더라도 본문 앵커보다 이쪽이 살아남아야 한다.
        int anchors = collectAnchors(entities, headline, false, 0);
        collectAnchors(entities, nullToEmpty(body), true, anchors);

        if (topicKeywords != null) {
            topicKeywords.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .filter(value -> value.length() >= 2)
                    .filter(value -> !BROAD_TOPIC_WORDS.contains(normalize(value)))
                    .filter(value -> normalizedHeadline.contains(normalize(value))
                            || normalizedBody.contains(normalize(value)))
                    .map(DeterministicEntityExtractor::canonicalKeyword)
                    .forEach(entities::add);
        }
        return new Extraction(
                Collections.unmodifiableSet(new LinkedHashSet<>(entities)),
                Collections.unmodifiableSet(new LinkedHashSet<>(organizations)));
    }

    /** 제목·요약에 직접 나온 닫힌 조직 사전만 돌려준다. 제품코드와 본문 배경 언급은 포함하지 않는다. */
    Set<String> extractOrganizations(String title, String summary) {
        Set<String> organizations = new LinkedHashSet<>();
        collectOrganizations(
                normalize(String.join("\n", nullToEmpty(title), nullToEmpty(summary))),
                organizations);
        return Collections.unmodifiableSet(organizations);
    }

    /** 제목에 명시된 조직만 비교한다. 요약·본문의 배경 언급은 제목 간 충돌을 해소하지 않는다. */
    Set<String> extractTitleOrganizations(String title) {
        String normalized = normalize(nullToEmpty(title));
        Set<String> organizations = new LinkedHashSet<>();
        collectOrganizations(normalized, organizations);
        TITLE_CONFLICT_ALIASES.forEach((canonical, aliases) -> {
            if (aliases.stream().anyMatch(alias -> containsAlias(normalized, alias))) {
                organizations.add(canonical);
            }
        });
        // The explicit "<subject> 주가," headline names the traded company even when
        // it is outside the alias dictionary. Keep it as a conflict profile only;
        // it must not become a positive entity-overlap vote from body background.
        if (organizations.isEmpty()) {
            Matcher stockSubject = STOCK_HEADLINE_SUBJECT.matcher(normalized);
            if (stockSubject.find() && !GENERAL_STOCK_SUBJECTS.contains(stockSubject.group(1))) {
                organizations.add(stockSubject.group(1));
            }
        }
        return Collections.unmodifiableSet(organizations);
    }

    private static void collectOrganizations(String normalized, Set<String> target) {
        ORGANIZATION_ALIASES.forEach((canonical, aliases) -> {
            if (aliases.stream()
                    .anyMatch(alias -> containsAlias(normalized, alias))) {
                target.add(canonical);
            }
        });
    }

    /** 별칭 양쪽의 문자 경계를 확인하되, 한글 뒤 조사는 조직명에 붙여 쓸 수 있게 허용한다. */
    private static boolean containsAlias(String text, String alias) {
        if (alias.isEmpty()) {
            return false;
        }
        int from = 0;
        while (from <= text.length() - alias.length()) {
            int index = text.indexOf(alias, from);
            if (index < 0) {
                return false;
            }
            int end = index + alias.length();
            boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean rightBoundary = end == text.length()
                    || !Character.isLetterOrDigit(text.charAt(end))
                    || hasKoreanPostposition(text, end);
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean hasKoreanPostposition(String text, int from) {
        return KOREAN_POSTPOSITIONS.stream().anyMatch(postposition -> {
            if (!text.startsWith(postposition, from)) {
                return false;
            }
            int end = from + postposition.length();
            return end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
        });
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
            if (!isEmailLocalPart(source, anchor)
                    && isUsableAnchor(value, requireDigit)
                    && entities.add(value)) {
                anchors++;
            }
        }
        return anchors;
    }

    /**
     * 기자 ID는 제품 코드와 같은 {@code 영문+숫자} 형태일 수 있으므로 값만 보고 제외하지 않는다.
     * 실제 이메일의 {@code @} 바로 앞에서 발견된 앵커만 사용자명으로 판정한다.
     */
    private static boolean isEmailLocalPart(String source, Matcher anchor) {
        return anchor.end() < source.length() && source.charAt(anchor.end()) == '@';
    }

    /** 사건을 가를 수 있는 앵커만 통과시킨다. 판정 순서는 싼 것부터다. */
    private static boolean isUsableAnchor(String value, boolean requireDigit) {
        return value.length() >= MIN_ANCHOR_LENGTH
                && !BROAD_TECHNICAL_ANCHORS.contains(value)
                && !GENERIC_ANCHORS.contains(value)
                && (!requireDigit || containsDigit(value));
    }

    private static boolean containsDigit(String value) {
        return value.chars().anyMatch(Character::isDigit);
    }

    private static Map<String, List<String>> organizationAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("삼성전자", List.of("삼성전자", "samsung electronics"));
        aliases.put("SK하이닉스", List.of("sk하이닉스", "sk하닉", "sk hynix"));
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
        aliases.put("어플라이드머티어리얼즈",
                List.of("어플라이드 머티어리얼즈", "어플라이드", "applied materials", "amat"));
        aliases.put("LG전자", List.of("lg전자", "lg electronics"));
        aliases.put("DGIST", List.of("dgist", "디지스트", "대구경북과학기술원"));
        aliases.put("한울반도체", List.of("한울반도체"));
        aliases.put("KB자산운용", List.of("kb자산운용", "kb운용"));
        aliases.put("에이블랩스", List.of("에이블랩스"));
        // "미래산업 육성" 같은 일반 명사구는 제외하고 법인 표기가 붙은 경우만 받는다.
        aliases.put("미래산업", List.of("미래산업(주)", "미래산업㈜", "미래산업 주식회사"));
        aliases.put("HPSP", List.of("hpsp"));
        aliases.put("CXMT", List.of("cxmt", "창신메모리"));
        aliases.replaceAll((canonical, values) -> values.stream()
                .map(DeterministicEntityExtractor::normalize)
                .distinct()
                .toList());
        return Collections.unmodifiableMap(aliases);
    }

    private static String canonicalKeyword(String value) {
        return value.chars().anyMatch(Character::isUpperCase)
                ? value
                : normalize(value);
    }

    private static String canonicalEntity(String value) {
        String normalized = normalize(value);
        return ORGANIZATION_ALIASES.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(alias -> containsAlias(normalized, alias)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(value.trim());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .transform(normalized -> WHITESPACE.matcher(normalized).replaceAll(" "))
                .trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Extraction(Set<String> entities, Set<String> organizations) {
    }
}
