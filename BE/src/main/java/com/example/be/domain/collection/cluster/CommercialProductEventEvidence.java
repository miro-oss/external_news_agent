package com.example.be.domain.collection.cluster;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Product commercialization identities require a maker, product, action and two independent facts. */
final class CommercialProductEventEvidence {
    private static final int LEAD_LIMIT = 1200;
    private static final String CATEGORY = "(?:프로세서|반도체|cpu|gpu|칩|스마트폰|노트북|로봇|전기차|자동차|제품|모델)";
    private static final Pattern PRODUCT_CONTEXT = Pattern.compile(CATEGORY);
    private static final Pattern QUOTED = Pattern.compile("['‘“\"]([^'’”\"\\n]{2,45})['’”\"]");
    private static final Pattern UNQUOTED = Pattern.compile(
            "(?<![가-힣a-z0-9])([가-힣a-z][가-힣a-z0-9-]{1,24}(?:\\s+v?[0-9]+(?:\\.[0-9]+)*)?)\\s+" + CATEGORY);
    private static final Pattern SUBJECT = Pattern.compile(
            "(?<![가-힣a-z0-9])([가-힣a-z][가-힣a-z0-9&.-]{1,35}?)(?:은|는|이|가)\\s+");
    private static final Pattern OWNER = Pattern.compile(
            "(?<![가-힣a-z0-9])([가-힣a-z][가-힣a-z0-9&.-]{1,35})의\\s+");
    private static final Pattern ACTION = Pattern.compile("수출|출시");
    private static final Pattern SPECULATION = Pattern.compile("것으로\\s*(?:예상|전망|관측)|가능성|추측|소문");
    private static final Pattern COMPLETED = Pattern.compile(
            "^(?:을|를)?\\s*(?:이미\\s*)?(?:시작했|개시했|완료했|단행했|했|하였|한\\s*바|에\\s*성공했)");
    private static final Pattern AGREEMENT = Pattern.compile("^(?:을|를)?\\s*(?:위한\\s*)?(?:계약|협약)(?:을|를)?\\s*체결");
    private static final Pattern REVIEW = Pattern.compile("^(?:을|를|하는\\s*방안을)?\\s*(?:검토|논의)");
    private static final Pattern PLANNED = Pattern.compile(
            "^(?:할|한다|하기로|하려|을|를|에)?\\s*(?:예정|계획|준비|추진|방침|목표|시작할|개시할|결정)|^(?:할|한다|하기로)");
    private static final Pattern PROCESS = Pattern.compile("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(?:nm|나노미터|나노)(?![a-z])");
    private static final Pattern PRODUCT_PROCESS_DETAIL = Pattern.compile(
            "^(?:(?:(?:이|해당|그|새|신형)\\s*)?" + CATEGORY + "(?:은|는|이|가|에|의)|(?:생산|제조)(?:에|는)|"
                    + "[0-9]+(?:\\.[0-9]+)?\\s*(?:nm|나노미터|나노))");
    private static final Pattern YEAR = Pattern.compile("(20[0-9]{2})년|내년|올해|금년|지난해|작년");
    private static final Pattern GENERATION = Pattern.compile("(?:v([0-9]+(?:\\.[0-9]+)*)|([0-9]+)\\s*세대)");
    private static final Pattern NAME_VERSION = Pattern.compile("^(.*?)(?:\\s*v?([0-9]+(?:\\.[0-9]+)*))$");
    private static final Pattern BACKGROUND = Pattern.compile("(?:^|(?<=[.!?])\\s+|\\n+)\\s*(?:한편|앞서|과거|일례로|참고로)\\s");
    private static final Pattern MARKET_FORECAST = Pattern.compile(
            "(?:시장|수요|점유율|출하량|판매량|시장조사).{0,65}(?:전망|예상|관측)|(?:전망|예상).{0,30}(?:수요|출하량)");
    private static final Pattern TITLE_ACTION = Pattern.compile("수출|출시|공략|진출|공급|판매|양산|생산|도전|내놓|선보");
    private static final Pattern TITLE_COMMENTARY = Pattern.compile("평가|비판|분석|전망|주가|점유율|출하량|수요");
    private static final Pattern TITLE_SUBJECT = Pattern.compile(
            "^([가-힣a-z][가-힣a-z0-9&.-]{1,35}?)(?:(?:은|는|이|가)\\s+|\\s+|[,，:：])");
    private static final Pattern TITLE_COUNTRY_PREFIX = Pattern.compile(
            "^(?:일본|미국|중국|한국|독일|대만|영국|인도|프랑스|캐나다|美|日|中|韓|英|獨|佛|臺)\\s+");
    private static final String REGION = "(?:인도네시아|아시아|북미|남미|유럽|중동|미국|중국|일본|한국|독일|인도|美|亞|中|日|韓)";
    private static final Pattern DESTINATION = Pattern.compile(
            "(" + REGION + "(?:\\s*(?:·|ㆍ|,|/|와|과|및)\\s*" + REGION + ")*)\\s*"
                    + "(?:시장(?:에|을|으로)?|지역(?:에|으로)?|으로|에|로|공략)");
    private static final Pattern REGION_TOKEN = Pattern.compile(REGION);
    private static final Set<String> GENERAL = Set.of(
            "업계", "회사", "업체", "기업", "시장", "제품", "신제품", "신형", "모델", "시리즈", "기술", "공정",
            "프로세서", "반도체", "칩", "cpu", "gpu", "로봇", "전기차", "자동차", "생산", "제조", "제조사", "관계자", "외신",
            "연구원", "이날", "전날", "내년", "올해", "지난해", "자사", "당사", "이번", "해당", "새로운", "차세대");

    private final Map<Long, Profile> profiles = new HashMap<>();

    CommercialProductEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String primary = foreground(normalize(ArticleEvidenceText.foreground(
                    ArticleEvidenceText.primary(article), LEAD_LIMIT)));
            profiles.put(article.articleId(), profile(title, primary, article.eventTime()));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || a.time() == null || b.time() == null
                || Duration.between(a.time(), b.time()).abs().compareTo(Duration.ofHours(48)) > 0
                || conflicts(left, right) || !a.maker().equals(b.maker()) || !a.name().equals(b.name())
                || !a.action().equals(b.action()) || !a.stage().equals(b.stage())) {
            return false;
        }
        int facts = intersects(a.processes(), b.processes()) ? 1 : 0;
        facts += intersects(a.markets(), b.markets()) ? 1 : 0;
        facts += a.year() != null && a.year().equals(b.year()) ? 1 : 0;
        return facts >= 2;
    }

    /** A product's missing facts do not veto an otherwise valid evidence family. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || !a.maker().equals(b.maker()) || !a.name().equals(b.name())) {
            return false;
        }
        return !a.action().equals(b.action()) || !a.stage().equals(b.stage())
                || different(a.year(), b.year()) || different(a.generation(), b.generation())
                || incompatibleMarkets(a.markets(), b.markets()) || disjointKnown(a.processes(), b.processes());
    }

    /** Only a fully supported focal maker may refine a noisy headline organization profile. */
    String subject(long articleId) {
        Profile profile = profiles.get(articleId);
        if (profile == null) {
            return null;
        }
        int facts = (profile.processes().isEmpty() ? 0 : 1) + (profile.markets().isEmpty() ? 0 : 1)
                + (profile.year() == null ? 0 : 1);
        return facts >= 2 ? profile.maker() : null;
    }

    private static Profile profile(String title, String primary, OffsetDateTime time) {
        if (primary.isBlank()) {
            return null;
        }
        String opening = firstSentence(primary);
        if (MARKET_FORECAST.matcher(opening).find()) {
            return null;
        }
        Matcher quoted = QUOTED.matcher(primary);
        while (quoted.find() && quoted.start() < 500) {
            int before = Math.max(0, quoted.start() - 65);
            int after = Math.min(primary.length(), quoted.end() + 35);
            if (!PRODUCT_CONTEXT.matcher(primary.substring(before, quoted.start())
                    + primary.substring(quoted.end(), after)).find()) {
                continue;
            }
            Profile result = candidate(title, primary, quoted.group(1), quoted.start(), quoted.end(), time);
            if (result != null) {
                return result;
            }
        }
        Matcher unquoted = UNQUOTED.matcher(primary);
        while (unquoted.find() && unquoted.start() < 500) {
            Profile result = candidate(title, primary, unquoted.group(1), unquoted.start(), unquoted.end(1), time);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static Profile candidate(String title, String primary, String rawName, int start, int end,
                                     OffsetDateTime time) {
        String name = rawName.replaceAll("\\([^)]*\\)", "").strip();
        if (!name.matches("[가-힣a-z][가-힣a-z0-9 .-]{1,34}") || name.split("\\s+").length > 3
                || GENERAL.contains(compact(name))) {
            return null;
        }
        int sentenceStart = sentenceStart(primary, start);
        String preceding = primary.substring(sentenceStart, start);
        String maker = maker(preceding);
        if (maker == null || !commercialHeadline(title, maker)) {
            return null;
        }
        int productSentenceEnd = sentenceEnd(primary, end);
        String context = primary.substring(sentenceStart, productSentenceEnd);
        Matcher action = ACTION.matcher(context);
        while (action.find()) {
            // A later sentence or another explicit subject cannot lend its action to this product.
            int productEnd = end - sentenceStart;
            if (action.start() < productEnd || action.start() - productEnd > 240
                    || changedSubject(context.substring(productEnd, action.start()), maker, name)) {
                continue;
            }
            int actionEnd = action.end() + subjectChange(context.substring(action.end()), maker, name);
            String tail = context.substring(action.end(), Math.min(actionEnd, action.end() + 65));
            if (SPECULATION.matcher(tail).find()) {
                continue;
            }
            String stage = stage(tail);
            if (stage == null) {
                continue;
            }
            String actionClause = context.substring(Math.max(0, action.start() - 160), actionEnd);
            String generation = generation(name, primary.substring(end, Math.min(primary.length(), end + 18)));
            Matcher version = NAME_VERSION.matcher(name);
            if (version.matches() && !version.group(1).isBlank()) {
                name = version.group(1);
            }
            Set<String> markets = destinations(actionClause);
            // A headline may name the destination omitted by its summary; it cannot invent a maker or action.
            if (titleNamesMaker(title, maker)) {
                markets.addAll(destinations(title));
            }
            return new Profile(maker, compact(name), generation, action.group(), stage,
                    actionYear(actionClause, time), processDetails(primary, context.substring(0, actionEnd),
                            productSentenceEnd, actionEnd == context.length(), maker, rawName),
                    Set.copyOf(markets), time);
        }
        return null;
    }

    private static boolean changedSubject(String text, String maker, String product) {
        return subjectChange(text, maker, product) < text.length();
    }

    private static int subjectChange(String text, String maker, String product) {
        int boundary = text.length();
        Matcher subject = SUBJECT.matcher(text);
        while (subject.find()) {
            String candidate = subject.group(1).replaceFirst("(?:에서|에)$", "");
            if (!GENERAL.contains(candidate) && !compact(candidate).equals(compact(product))
                    && !DeterministicEntityExtractor.canonicalSubject(candidate).equals(maker)) {
                boundary = Math.min(boundary, subject.start());
                break;
            }
        }
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) {
            if (!compact(quoted.group(1)).equals(compact(product))) {
                boundary = Math.min(boundary, quoted.start());
                break;
            }
        }
        return boundary;
    }

    private static Set<String> processDetails(String primary, String productSentence, int sentenceEnd,
                                               boolean sameSubjectAtEnd, String maker, String product) {
        Set<String> result = new HashSet<>(processes(productSentence));
        String remaining = primary.substring(sentenceEnd).replaceFirst("^[.!?\\s]+", "");
        String nextSentence = firstSentence(remaining);
        // Only the immediately following product/process description inherits this product's subject.
        // Other manufacturers' process facts and later background never enter the identity.
        if (sameSubjectAtEnd && PRODUCT_PROCESS_DETAIL.matcher(nextSentence).find()
                && !changedSubject(nextSentence, maker, product)) {
            result.addAll(processes(nextSentence));
        }
        return Set.copyOf(result);
    }

    private static String maker(String preceding) {
        Matcher owner = OWNER.matcher(preceding);
        String explicitOwner = null;
        while (owner.find()) {
            String ownedDescription = preceding.substring(owner.end());
            if (!GENERAL.contains(owner.group(1)) && ownedDescription.length() < 90
                    && !Pattern.compile("공정|생산|제조|위탁|공장|시장|점유율").matcher(ownedDescription).find()) {
                explicitOwner = owner.group(1);
            }
        }
        if (explicitOwner != null) {
            return DeterministicEntityExtractor.canonicalSubject(explicitOwner);
        }
        Matcher subject = SUBJECT.matcher(preceding);
        String result = null;
        while (subject.find()) {
            if (!GENERAL.contains(subject.group(1)) && preceding.length() - subject.end() < 200) {
                result = DeterministicEntityExtractor.canonicalSubject(subject.group(1));
            }
        }
        return result;
    }

    private static boolean titleNamesMaker(String title, String maker) {
        if (new DeterministicEntityExtractor().extractTitleOrganizations(title).contains(maker)) {
            return true;
        }
        for (String token : title.split("[^가-힣a-z0-9&.-]+")) {
            if (DeterministicEntityExtractor.canonicalSubject(token.replaceFirst("(?:은|는|이|가|의)$", "")).equals(maker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean commercialHeadline(String title, String maker) {
        String unquoted = QUOTED.matcher(title).replaceAll(" ").strip();
        if (!TITLE_ACTION.matcher(unquoted).find() || TITLE_COMMENTARY.matcher(unquoted).find()) {
            return false;
        }
        // A competitor-market introduction describes the destination of the entrant's action.
        // A separate evaluation or another company's announcement does not have this structure.
        unquoted = unquoted.replaceFirst("^.{0,90}?(?:독점|장악).{0,20}?(?:시장|아성)(?:에서|에)?\\s*", "");
        String[] clauses = unquoted.split("…+|\\.{3,}|[\\n;]");
        String firstClause = null;
        for (String rawClause : clauses) {
            String clause = rawClause.strip();
            // Removing an opening quotation can leave an empty clause before the actual headline.
            if (clause.isBlank()) {
                continue;
            }
            if (firstClause != null && !firstClause.matches(".*(?:독점|장악).*")) {
                return false;
            }
            if (firstClause == null) {
                firstClause = clause;
            }
            clause = TITLE_COUNTRY_PREFIX.matcher(clause).replaceFirst("");
            Matcher subject = TITLE_SUBJECT.matcher(clause);
            if (subject.find()) {
                String candidate = DeterministicEntityExtractor.canonicalSubject(subject.group(1));
                if (candidate.equals(maker)) {
                    return TITLE_ACTION.matcher(clause).find();
                }
                if (!clause.matches(".*(?:독점|장악).*")) {
                    return false;
                }
            }
        }
        return false;
    }

    private static String stage(String tail) {
        if (COMPLETED.matcher(tail).find()) return "COMPLETED";
        if (AGREEMENT.matcher(tail).find()) return "CONTRACT";
        if (REVIEW.matcher(tail).find()) return "REVIEW";
        return PLANNED.matcher(tail).find() ? "PLAN" : null;
    }

    private static String generation(String name, String following) {
        Matcher version = NAME_VERSION.matcher(name);
        if (version.matches() && !version.group(1).isBlank()) {
            return version.group(2);
        }
        Matcher explicit = GENERATION.matcher(following);
        return explicit.find() ? explicit.group(1) == null ? explicit.group(2) : explicit.group(1) : null;
    }

    private static Integer actionYear(String clause, OffsetDateTime time) {
        Matcher years = YEAR.matcher(clause);
        Integer value = null;
        while (years.find()) {
            Integer candidate = years.group(1) == null
                    ? time == null ? null : time.getYear() + switch (years.group()) {
                        case "내년" -> 1;
                        case "지난해", "작년" -> -1;
                        default -> 0;
                    } : Integer.valueOf(years.group(1));
            if (value != null && candidate != null && !value.equals(candidate)) {
                return null;
            }
            value = candidate;
        }
        return value;
    }

    private static Set<String> processes(String text) {
        Set<String> result = new HashSet<>();
        Matcher process = PROCESS.matcher(text);
        while (process.find()) {
            result.add(new BigDecimal(process.group(1)).stripTrailingZeros().toPlainString());
        }
        return Set.copyOf(result);
    }

    private static Set<String> destinations(String text) {
        Set<String> result = new HashSet<>();
        Matcher destination = DESTINATION.matcher(text);
        while (destination.find()) {
            Matcher region = REGION_TOKEN.matcher(destination.group(1));
            while (region.find()) {
                result.add(switch (region.group()) {
                    case "美" -> "미국";
                    case "亞" -> "아시아";
                    case "中" -> "중국";
                    case "日" -> "일본";
                    case "韓" -> "한국";
                    default -> region.group();
                });
            }
        }
        return result;
    }

    private static String foreground(String text) {
        Matcher background = BACKGROUND.matcher(text);
        return background.find() ? text.substring(0, background.start()) : text;
    }

    private static int sentenceStart(String text, int before) {
        int start = 0;
        Matcher boundary = Pattern.compile("[.!?]\\s+|\\n+").matcher(text.substring(0, before));
        while (boundary.find()) start = boundary.end();
        return start;
    }

    private static int sentenceEnd(String text, int after) {
        Matcher boundary = Pattern.compile("[.!?](?=\\s|$)|\\n+").matcher(text);
        return boundary.find(after) ? boundary.start() : text.length();
    }

    private static String firstSentence(String text) {
        return text.substring(0, sentenceEnd(text, 0));
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    private static boolean disjointKnown(Set<String> a, Set<String> b) {
        return !a.isEmpty() && !b.isEmpty() && !intersects(a, b);
    }

    private static boolean incompatibleMarkets(Set<String> a, Set<String> b) {
        // A shorter account may name only one destination. Two different explicit extensions
        // of that destination are separate scopes even though they share a country.
        return !a.isEmpty() && !b.isEmpty() && !a.containsAll(b) && !b.containsAll(a);
    }

    private static boolean different(Object a, Object b) {
        return a != null && b != null && !a.equals(b);
    }

    private static String compact(String text) {
        return normalize(text).replaceAll("\\s+", "");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record Profile(String maker, String name, String generation, String action, String stage,
                           Integer year, Set<String> processes, Set<String> markets, OffsetDateTime time) {
    }
}
