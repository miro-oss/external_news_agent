package com.example.be.domain.collection.cluster;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A maker's capacity plan needs two process-specific monthly volumes at one target period. */
final class ProductionCapacityEventEvidence {
    private static final int CONTEXT_LIMIT = 2200;
    private static final Pattern SUBJECT = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z가-힣][a-z0-9가-힣&.-]{1,34}?)(?:은|는|이|가|의|도)\\s+");
    private static final Set<String> GENERAL = Set.of(
            "회사", "업체", "기업", "시장", "업계", "정부", "소식통", "관계자", "매체", "언론", "보도", "목표",
            "생산량", "생산능력", "능력", "설비", "공장", "공정", "제품", "반도체", "수요", "주문", "계획",
            "전망", "규모", "올해", "내년", "지난해", "내년도", "이번", "이날", "고객", "고객사", "협력사");
    private static final Pattern CAPACITY = Pattern.compile("생산\\s*(?:능력|량|실적)|증산|증설|생산\\s*확대|케파|캐파");
    private static final Pattern TITLE_FOCUS = Pattern.compile(
            "증산|증설|생산|케파|캐파|월\\s*[0-9][0-9,.]*\\s*(?:만|천)?\\s*장");
    private static final Pattern MONTHLY = Pattern.compile("월\\s*(?:생산|[0-9])|매월|월간|장\\s*/\\s*월");
    private static final Pattern PROCESS = Pattern.compile("(?<![a-z0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(?:nm|나노(?:미터)?)");
    private static final Pattern PLAN = Pattern.compile("목표|계획|예정|전망|예상|늘릴|늘린|늘어날|늘어난|확대할|확대한다|확대된다|증산한다");
    private static final Pattern ACTUAL = Pattern.compile("달성했|달성한|집계됐|집계된|기록했|생산했|생산한|생산하고\\s*있|확대했|늘렸|증산을?\\s*완료|가동하고\\s*있");
    private static final Pattern DENIED = Pattern.compile("취소|철회|중단|무산|부인|사실무근|않|불발");
    private static final Pattern BACKGROUND = Pattern.compile("(?:^|[.!?]\\s+|\\n+)\\s*(?:한편|앞서|과거|참고로)\\s*");
    private static final Pattern OLD_PLAN = Pattern.compile("^(?:당시|종전|기존|과거)|(?:과거|종전|기존|지난해|작년).{0,15}(?:계획|목표)");
    private static final Pattern TARGET = Pattern.compile(
            "(?:(20[0-9]{2})년|(내년|올해|금년))\\s*(중반|상반기|하반기|말|초|[1-4]\\s*분기|[0-9]{1,2}\\s*월)");
    private static final Pattern VOLUME = Pattern.compile("(?<![0-9.])([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(만|천)?\\s*장");
    private static final Pattern APPROXIMATE_BEFORE = Pattern.compile("(?:약|최대|최소|[~∼〜–—-])\\s*$");
    private static final Pattern APPROXIMATE_AFTER = Pattern.compile("^\\s*(?:이상|이하|내외|가량|[~∼〜–—-])");
    private final Map<Long, Profile> profiles = new HashMap<>();

    ProductionCapacityEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            if (!article.hasFullText()) continue;
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleEvidenceText.foreground(article.body(), CONTEXT_LIMIT));
            Matcher background = BACKGROUND.matcher(body);
            if (background.find()) body = body.substring(0, background.start());
            Profile profile = profile(title, body, article.eventTime());
            if (profile != null) profiles.put(article.articleId(), profile);
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return a != null && b != null && a.stage() == Stage.PLAN && b.stage() == Stage.PLAN
                && a.owner().equals(b.owner()) && a.target().equals(b.target()) && !volumeConflict(a, b)
                && a.volumes().keySet().stream().filter(b.volumes()::containsKey).count() >= 2;
    }

    /** Extra or omitted processes are unknown; shared processes can expose a quantity conflict. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return a != null && b != null && (!a.owner().equals(b.owner()) || a.stage() != b.stage()
                || !a.target().equals(b.target()) || volumeConflict(a, b));
    }

    private static boolean volumeConflict(Profile a, Profile b) {
        return a.volumes().entrySet().stream().anyMatch(entry -> b.volumes().containsKey(entry.getKey())
                && entry.getValue().compareTo(b.volumes().get(entry.getKey())) != 0);
    }

    private static Profile profile(String title, String body, OffsetDateTime publication) {
        if (!TITLE_FOCUS.matcher(title).find() || DENIED.matcher(title).find()
                || !CAPACITY.matcher(bound(body, 500)).find()) return null;
        Set<String> owners = new HashSet<>();
        for (String sentence : bound(body, 500).split("(?<=[.!?])\\s+|\\n+")) {
            Matcher capacity = CAPACITY.matcher(sentence);
            if (!capacity.find()) continue;
            int boundary = capacity.start();
            Matcher process = PROCESS.matcher(sentence);
            if (process.find()) boundary = Math.min(boundary, process.start());
            Matcher target = TARGET.matcher(sentence);
            if (target.find()) boundary = Math.min(boundary, target.start());
            // Only an explicit subject before the capacity clause establishes its maker.
            // Copied headlines and later comparison prose cannot supply another owner.
            Matcher subjects = SUBJECT.matcher(sentence.substring(0, boundary));
            while (subjects.find()) {
                String raw = subjects.group(1);
                String canonical = DeterministicEntityExtractor.canonicalSubject(raw);
                if (!GENERAL.contains(raw) && DeterministicEntityExtractor.mentionsSubject(title, canonical)) {
                    owners.add(canonical);
                }
            }
        }
        if (owners.size() != 1) return null;
        String owner = owners.iterator().next();
        Map<Target, Map<String, BigDecimal>> byTarget = new HashMap<>();
        Set<Stage> stages = new HashSet<>();
        for (String rawSentence : body.split("(?<=[.!?])\\s+|\\n+")) {
            String sentence = rawSentence.strip();
            if (OLD_PLAN.matcher(sentence).find()) continue;
            if (DENIED.matcher(sentence).find() && CAPACITY.matcher(sentence).find()) return null;
            // Calendar months ("2028년 6월 7만장") are target dates, not monthly units.
            String withoutTargets = TARGET.matcher(sentence).replaceAll("");
            if (!MONTHLY.matcher(withoutTargets).find() || anotherSubject(sentence, owner)) continue;
            Matcher process = PROCESS.matcher(sentence);
            if (!process.find()) continue;
            String node = decimal(process.group(1)).toPlainString();
            // An unassigned '2/3 nm respectively ...' table is not two identified facts.
            if (process.find()) continue;
            boolean planned = PLAN.matcher(sentence).find();
            boolean actual = ACTUAL.matcher(sentence).find();
            if (planned == actual) continue;
            Matcher target = TARGET.matcher(sentence);
            while (target.find()) {
                Target point = target(target, publication);
                if (point == null) continue;
                String following = sentence.substring(target.end());
                Matcher next = TARGET.matcher(following);
                if (next.find()) following = following.substring(0, next.start());
                Matcher volume = VOLUME.matcher(following);
                if (!volume.find()) continue;
                if (APPROXIMATE_BEFORE.matcher(following.substring(0, volume.start())).find()
                        || APPROXIMATE_AFTER.matcher(following.substring(volume.end())).find()) continue;
                BigDecimal value = decimal(volume.group(1)).multiply(switch (volume.group(2) == null ? "" : volume.group(2)) {
                    case "만" -> BigDecimal.valueOf(10000);
                    case "천" -> BigDecimal.valueOf(1000);
                    default -> BigDecimal.ONE;
                }).stripTrailingZeros();
                if (value.signum() <= 0 || volume.find()) continue;
                Map<String, BigDecimal> values = byTarget.computeIfAbsent(point, ignored -> new HashMap<>());
                if (values.containsKey(node) && values.get(node).compareTo(value) != 0) return null;
                values.put(node, value);
                stages.add(planned ? Stage.PLAN : Stage.ACTUAL);
            }
        }
        if (stages.size() != 1 || byTarget.isEmpty()) return null;
        // Current-year ramp-up is often followed by next year's end target. Never
        // let a matching intermediate value hide a changed final target.
        int latest = byTarget.keySet().stream().mapToInt(Target::order).max().orElseThrow();
        List<Target> latestPoints = byTarget.keySet().stream().filter(point -> point.order() == latest).toList();
        if (latestPoints.size() != 1) return null;
        Target target = latestPoints.getFirst();
        Map<String, BigDecimal> values = byTarget.get(target);
        if (values.size() < 2) return null;
        return new Profile(owner, stages.iterator().next(), target, Map.copyOf(values));
    }

    private static boolean anotherSubject(String sentence, String owner) {
        Matcher volume = VOLUME.matcher(sentence);
        if (!volume.find()) return true;
        // Look for ownership before the first quantity, including "next year,
        // another maker ...". Remove target phrases so "중반에는" cannot become
        // a company; verbs after the quantity ("확대하는") cannot supply owners.
        String prefix = TARGET.matcher(sentence.substring(0, volume.start())).replaceAll("");
        Matcher subjects = SUBJECT.matcher(prefix);
        while (subjects.find()) {
            String raw = subjects.group(1);
            if (!GENERAL.contains(raw) && !DeterministicEntityExtractor.canonicalSubject(raw).equals(owner)) return true;
        }
        return false;
    }

    private static Target target(Matcher matcher, OffsetDateTime publication) {
        if (matcher.group(1) == null && publication == null) return null;
        int year = matcher.group(1) != null ? Integer.parseInt(matcher.group(1))
                : publication.getYear() + (matcher.group(2).equals("내년") ? 1 : 0);
        String phase = matcher.group(3).replaceAll("\\s", "");
        int month = switch (phase) {
            case "초" -> 1;
            case "중반", "상반기" -> 6;
            case "말", "하반기" -> 12;
            default -> phase.endsWith("분기") ? Integer.parseInt(phase.substring(0, 1)) * 3
                    : Integer.parseInt(phase.substring(0, phase.length() - 1));
        };
        return month < 1 || month > 12 ? null : new Target(year, phase, year * 12 + month);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value.replace(",", "")).stripTrailingZeros(); }
    private static String bound(String value, int limit) { return value.substring(0, Math.min(value.length(), limit)); }
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private enum Stage { PLAN, ACTUAL }
    private record Target(int year, String phase, int order) {}
    private record Profile(String owner, Stage stage, Target target, Map<String, BigDecimal> volumes) {}
}
