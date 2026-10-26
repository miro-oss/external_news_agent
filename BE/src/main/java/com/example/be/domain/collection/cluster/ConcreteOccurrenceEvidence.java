package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dated hosted occasions and public calls can have different headline subjects. */
final class ConcreteOccurrenceEvidence {
    private static final int CONTEXT_LIMIT = 2200;
    private static final String OCCASION_KIND = "(?:토크\\s*콘서트|간담회|컨퍼런스|콘퍼런스|포럼|서밋|심포지엄|세미나|설명회)";
    private static final Pattern OCCASION = Pattern.compile(
            "['‘“\"]([^'’”\"\\n]{2,100}?" + OCCASION_KIND
                    + "(?:\\s*\\([^)]{1,60}\\))?(?:\\s+(?:20[0-9]{2}|제?\\s*[0-9]+회))?)['’”\"]");
    private static final String HOST_SUFFIX = "(?:연구원|연구소|위원회|협회|재단|대학교|대학|그룹)";
    private static final Pattern HOST = Pattern.compile(
            "(?<![a-z0-9가-힣])((?:[a-z][a-z0-9&-]*[ \\t]+){0,2}[a-z][a-z0-9&-]*" + HOST_SUFFIX
                    + "|[가-힣]{2,25}[ \\t]+[a-z][a-z0-9&-]*" + HOST_SUFFIX
                    + "|[a-z가-힣][a-z0-9가-힣&-]*?" + HOST_SUFFIX
                    + ")(?=(?:은|는|이|가|의|에서)?(?:[\\s,，.!?]|$))");
    private static final Pattern OCCASION_ACTION = Pattern.compile("개최|열린|열고|열었|진행|발표|공개|소개|내놓");
    private static final Pattern VENUE = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z가-힣][a-z0-9가-힣]{1,30}(?:사이언스파크|컨벤션센터|시민회관|회관|캠퍼스|호텔))");
    private static final Pattern EDITION = Pattern.compile("(?:^|\\s)(20[0-9]{2}|제?\\s*[0-9]+회)$");
    private static final Pattern SENTENCE = Pattern.compile("[^.!?\\n]+(?:[.!?]|$)");
    private static final Pattern BACKGROUND = Pattern.compile(
            "(?:^|[.!?]\\s+|\\n+)\\s*(?:한편|앞서|과거|일례로|관련\\s*소식)\\s*");
    private static final Pattern HISTORICAL = Pattern.compile("전날|어제|그제|지난\\s*(?:주|달|해|[0-9]+월)|작년|재작년|내년");
    private static final Pattern DAY = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?(?<![0-9])([0-9]{1,2})일(?!간|째|차|\\s*(?:동안|만에))");
    private static final Pattern REPORTING_PREFIX = Pattern.compile("^\\s*[0-9]{1,2}일\\s+[^.!?]{1,35}?따르면\\s*");
    private static final Pattern CALL = Pattern.compile(
            "전화(?:를)?\\s*(?:걸었|걸어|받았|받아|받고|해|했)|통화(?:했|해|하던|를\\s*(?:했|나눴))");
    private static final Pattern QUOTED_WORDS = Pattern.compile("\"[^\"]*(?:\"|$)|“[^”]*(?:”|$)|‘[^’]*(?:’|$)|'[^']*(?:'|$)");
    private static final Pattern NON_OCCURRENCE = Pattern.compile(
            "부인(?:했|하|한)|취소(?:했|하|한)|철회(?:했|하|한)|거절(?:했|하|한)"
                    + "|(?:지|지는)\\s*(?:않|못)|(?:사실|적)(?:이|은)?\\s*없"
                    + "|(?:해|걸어|받아)\\s*달라|(?:요청|부탁)(?:했|하|한)"
                    + "|(?:개최|진행|통화).{0,12}(?:예정|계획)");
    private static final Pattern PUBLIC_CALL = Pattern.compile("스피커폰|무대|서밋|컨퍼런스|콘퍼런스|공개(?:된)?\\s*(?:전화|통화)");
    private static final String ROLE = "(?:최고경영자(?:\\s*\\(ceo\\))?|ceo|대통령|총리|회장|대표)";
    // A named participant is introduced with an organization/country and role. Bare
    // shared surnames are aliases only after a unique full introduction in this body.
    private static final Pattern PARTICIPANT = Pattern.compile(
            "(?<![a-z0-9가-힣])([가-힣]{1,12}(?:[ \\t]+[가-힣]{1,12})?)[ \\t]+"
                    + "([a-z가-힣][a-z0-9가-힣&.-]{1,30})[ \\t]+(" + ROLE + ")");
    private static final Set<String> NAME_PREFIXES = Set.of("이날", "당시", "또한", "앞서", "한편", "이번", "따르면");
    private static final Pattern PHOTO = Pattern.compile("사진\\s*[=:]|재판매|db\\s*금지");
    private final Map<Long, Profile> profiles = new HashMap<>();

    ConcreteOccurrenceEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            if (!article.hasFullText()) {
                continue;
            }
            String title = normalize(detector.coreTitle(article.title()));
            String body = foreground(bound(normalize(ArticleEvidenceText.primary(article)), CONTEXT_LIMIT));
            List<Sentence> sentences = sentences(body);
            profiles.put(article.articleId(), new Profile(
                    hosted(title, body, sentences, article.eventTime()),
                    publicCall(title, body, sentences, article.eventTime())));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || conflicts(left, right)) {
            return false;
        }
        Hosted x = a.hosted();
        Hosted y = b.hosted();
        if (x != null && y != null && x.host().equals(y.host()) && x.name().equals(y.name())
                && sameKnown(x.day(), y.day()) && !different(x.edition(), y.edition())) {
            return true;
        }
        PublicCall p = a.call();
        PublicCall q = b.call();
        return p != null && q != null && p.participants().equals(q.participants())
                && sameKnown(p.day(), q.day());
    }

    /** Explicit occurrence differences remain vetoes when another article is underspecified. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null) {
            return false;
        }
        Hosted x = a.hosted();
        Hosted y = b.hosted();
        if (x != null && y != null && x.name().equals(y.name())
                && (!x.host().equals(y.host()) || different(x.day(), y.day())
                || different(x.edition(), y.edition()) || different(x.venue(), y.venue()))) {
            return true;
        }
        PublicCall p = a.call();
        PublicCall q = b.call();
        return p != null && q != null && !disjoint(p.participants(), q.participants())
                && (!p.participants().equals(q.participants()) || different(p.day(), q.day())
                || different(p.occasion(), q.occasion()) || different(p.edition(), q.edition()));
    }

    private static Hosted hosted(String title, String body, List<Sentence> sentences, OffsetDateTime time) {
        Set<Hosted> candidates = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Sentence sentence : sentences) {
            Matcher named = OCCASION.matcher(sentence.text());
            while (named.find()) {
                if (!OCCASION_ACTION.matcher(sentence.text()).find() || PHOTO.matcher(paragraph(body, sentence.start())).find()
                        || HISTORICAL.matcher(sentence.text()).find() || nonOccurrence(sentence.text())) {
                    continue;
                }
                Set<String> hosts = new HashSet<>();
                collect(HOST, bound(body, 350), hosts);
                collect(HOST, sentence.text(), hosts);
                if (hosts.size() != 1) {
                    return null;
                }
                String host = hosts.iterator().next();
                String brand = compact(host).replaceFirst("(?:ai)?" + HOST_SUFFIX + "$", "");
                if (brand.length() < 2 || !compact(title).contains(brand)) {
                    continue;
                }
                Occasion occasion = occasion(named.group(1), brand);
                names.add(occasion.name());
                Set<String> venues = new HashSet<>();
                collect(VENUE, body.substring(0, sentence.end()), venues);
                candidates.add(new Hosted(compact(host), occasion.name(), occasion.edition(),
                        eventDay(sentence, body, time), only(venues)));
            }
        }
        // Several event dates/editions or named programs are not one occurrence.
        return candidates.size() == 1 && names.size() == 1 ? candidates.iterator().next() : null;
    }

    private static PublicCall publicCall(String title, String body, List<Sentence> sentences, OffsetDateTime time) {
        List<Participant> people = participants(body);
        Set<PublicCall> candidates = new HashSet<>();
        for (Sentence sentence : sentences) {
            if (!CALL.matcher(sentence.text()).find() || PHOTO.matcher(paragraph(body, sentence.start())).find()
                    || HISTORICAL.matcher(sentence.text()).find() || nonOccurrence(sentence.text())) {
                continue;
            }
            if (!PUBLIC_CALL.matcher(sentence.text()).find()) {
                continue;
            }
            Set<String> involved = new HashSet<>();
            boolean headlinePerson = false;
            for (Participant person : people) {
                if (person.aliases().stream().anyMatch(alias -> containsPhrase(sentence.text(), alias))) {
                    involved.add(person.identity());
                    headlinePerson |= compact(title).contains(compact(person.name()))
                            || (person.surname().length() >= 2 && title.contains(person.surname()));
                }
            }
            if (involved.size() != 2 || !headlinePerson) {
                continue;
            }
            Set<Occasion> occasions = new HashSet<>();
            Matcher named = OCCASION.matcher(sentence.text());
            while (named.find()) {
                occasions.add(occasion(named.group(1), ""));
            }
            if (occasions.size() > 1) {
                return null;
            }
            Occasion occasion = occasions.isEmpty() ? null : occasions.iterator().next();
            candidates.add(new PublicCall(Set.copyOf(involved), eventDay(sentence, body, time),
                    occasion == null ? null : occasion.name(), occasion == null ? null : occasion.edition()));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Repeated descriptions may omit the event name but may not disagree about it.
        PublicCall first = candidates.iterator().next();
        Set<String> names = new HashSet<>();
        Set<String> editions = new HashSet<>();
        Set<LocalDate> days = new HashSet<>();
        for (PublicCall candidate : candidates) {
            if (!candidate.participants().equals(first.participants())) {
                return null;
            }
            if (candidate.day() != null) days.add(candidate.day());
            if (candidate.occasion() != null) names.add(candidate.occasion());
            if (candidate.edition() != null) editions.add(candidate.edition());
        }
        if (days.size() > 1 || names.size() > 1 || editions.size() > 1) {
            return null;
        }
        return new PublicCall(first.participants(), only(days), only(names), only(editions));
    }

    private static List<Participant> participants(String body) {
        Map<String, Participant> named = new LinkedHashMap<>();
        Matcher matcher = PARTICIPANT.matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1).replaceAll("[ \\t]+", " ");
            String[] words = name.split(" ");
            if (nonName(words[words.length - 1])) {
                continue;
            }
            if (words.length == 2 && nonName(words[0])) {
                name = words[1];
            }
            if (name.length() < 2 || nonName(name)) {
                continue;
            }
            String role = matcher.group(3).startsWith("최고경영자") ? "ceo" : matcher.group(3);
            String surname = name.substring(name.lastIndexOf(' ') + 1);
            String identity = compact(name) + ":" + matcher.group(2) + ":" + role;
            named.putIfAbsent(identity, new Participant(identity, name, surname, role,
                    new HashSet<>(Set.of(matcher.group()))));
        }
        List<Participant> people = new ArrayList<>(named.values());
        for (Participant person : people) {
            if (people.stream().filter(other -> other.role().equals(person.role())
                    && other.surname().equals(person.surname())).count() == 1) {
                person.aliases().add(person.surname() + " " + person.role());
                person.aliases().add(person.name() + " " + person.role());
            }
        }
        return people;
    }

    private static boolean nonOccurrence(String sentence) {
        // A quote may deny a policy claim during a completed call. Only the
        // surrounding report can deny/cancel/request the occurrence itself.
        return NON_OCCURRENCE.matcher(QUOTED_WORDS.matcher(sentence).replaceAll(" ")).find();
    }

    private static boolean nonName(String value) {
        return NAME_PREFIXES.contains(value) || value.endsWith("에서") || value.endsWith("에게")
                || value.endsWith("에는") || value.endsWith("도중") || value.endsWith("의");
    }

    private static LocalDate eventDay(Sentence sentence, String body, OffsetDateTime time) {
        String text = REPORTING_PREFIX.matcher(sentence.text()).replaceFirst("");
        Set<LocalDate> dates = dates(text, time);
        if (dates == null || dates.size() > 1) {
            return null;
        }
        if (dates.size() == 1) {
            return dates.iterator().next();
        }
        if (!text.contains("이날")) {
            return null;
        }
        // An anaphor can reuse a unique written date, never the publication date alone.
        dates = dates(body.substring(0, sentence.start()), time);
        return dates == null ? null : only(dates);
    }

    private static Set<LocalDate> dates(String text, OffsetDateTime time) {
        if (HISTORICAL.matcher(text).find()) {
            return null;
        }
        Set<LocalDate> dates = new HashSet<>();
        Matcher matcher = DAY.matcher(text);
        while (matcher.find()) {
            if (time == null && (matcher.group(1) == null || matcher.group(2) == null)) {
                return null;
            }
            try {
                LocalDate date = LocalDate.of(matcher.group(1) == null ? time.getYear() : Integer.parseInt(matcher.group(1)),
                        matcher.group(2) == null ? time.getMonthValue() : Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                if (matcher.group(2) == null && time != null && date.isAfter(time.toLocalDate())) {
                    return null;
                }
                dates.add(date);
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        return dates;
    }

    private static Occasion occasion(String raw, String hostBrand) {
        String text = raw.replaceAll("\\([^)]*\\)", " ").strip();
        Matcher edition = EDITION.matcher(text);
        String year = null;
        if (edition.find()) {
            year = compact(edition.group(1));
            text = text.substring(0, edition.start());
        }
        String name = compact(text);
        if (!hostBrand.isEmpty() && name.startsWith(hostBrand)) {
            name = name.substring(hostBrand.length());
        }
        return new Occasion(name, year);
    }

    private static List<Sentence> sentences(String body) {
        List<Sentence> result = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(body);
        while (matcher.find()) {
            result.add(new Sentence(matcher.start(), matcher.end(), matcher.group().strip()));
        }
        return result;
    }

    private static String paragraph(String body, int offset) {
        int start = body.lastIndexOf('\n', offset);
        int end = body.indexOf('\n', offset);
        return body.substring(start < 0 ? 0 : start + 1, end < 0 ? body.length() : end);
    }

    private static boolean containsPhrase(String text, String phrase) {
        return Pattern.compile("(?<![a-z0-9가-힣])" + Pattern.quote(phrase)
                + "(?=$|[^a-z0-9가-힣]|은|는|이|가|의|도|에게|과|와)").matcher(text).find();
    }

    private static void collect(Pattern pattern, String text, Set<String> values) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).replaceAll("\\s+", " "));
        }
    }

    private static <T> T only(Set<T> values) { return values.size() == 1 ? values.iterator().next() : null; }
    private static <T> boolean sameKnown(T a, T b) { return a != null && a.equals(b); }
    private static <T> boolean different(T a, T b) { return a != null && b != null && !a.equals(b); }
    private static boolean disjoint(Set<String> a, Set<String> b) { return a.stream().noneMatch(b::contains); }
    private static String compact(String value) { return value.replaceAll("[^a-z0-9가-힣]", ""); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static String bound(String value, int length) { return value.substring(0, Math.min(value.length(), length)); }
    private static String foreground(String value) {
        Matcher background = BACKGROUND.matcher(value);
        return background.find() ? value.substring(0, background.start()) : value;
    }

    private record Sentence(int start, int end, String text) {}
    private record Occasion(String name, String edition) {}
    private record Hosted(String host, String name, String edition, LocalDate day, String venue) {}
    private record PublicCall(Set<String> participants, LocalDate day, String occasion, String edition) {}
    private record Participant(String identity, String name, String surname, String role, Set<String> aliases) {}
    private record Profile(Hosted hosted, PublicCall call) {}
}
