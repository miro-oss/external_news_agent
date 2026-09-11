package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A single company's observed price move on an explicit trading day and exchange. */
final class StockEventEvidence {
    private static final Pattern HEADLINE = Pattern.compile("주가|증시|급등|폭등|나홀로|강세|급락");
    private static final Pattern UP = Pattern.compile("(?:상승|급등|폭등)(?:했|한|하며|해)|강세를?\\s*(?:보였|보인)|올랐|오른");
    private static final Pattern DOWN = Pattern.compile("(?:하락|급락|폭락)(?:했|한|하며|해)|약세를?\\s*(?:보였|보인)|내렸|내린");
    private static final Pattern TRADING_CONTEXT = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?(?<![0-9])([0-9]{1,2})일"
                    + "\\s*(?:\\(현지시간\\))?\\s*(뉴욕\\s*증시|미국\\s*증시|코스닥|코스피|유가증권시장)");
    private static final Pattern STOCK_SUBJECT = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z0-9가-힣&.-]{2,40}?)(?:은|는|의|이|가)?\\s+주가");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?:\\s|$)|\\n");
    private static final Pattern FORECAST = Pattern.compile("예상|전망|가능성|기대|추정");
    private final Map<Long, Profile> profiles = new HashMap<>();

    StockEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()));
            String lead = bound(body.isBlank() ? normalize(article.summary()) : body, 350);
            Matcher trading = TRADING_CONTEXT.matcher(lead);
            if (!HEADLINE.matcher(title).find() || !trading.find()) {
                continue;
            }
            String clause = bound(lead.substring(trading.end()), 180);
            Matcher end = SENTENCE_END.matcher(clause);
            if (end.find()) {
                clause = clause.substring(0, end.start());
            }
            Matcher subject = STOCK_SUBJECT.matcher(clause);
            if (!subject.find()) {
                continue;
            }
            // Parse the noun phrase attached to 'stock price', not any company mentioned
            // in its surrounding story. Unknown suppliers cannot inherit a customer's identity.
            Set<String> organizations = extractor.extractOrganizations(subject.group(1), null);
            String movement = clause.substring(subject.end());
            boolean up = UP.matcher(movement).find();
            boolean down = DOWN.matcher(movement).find();
            LocalDate day = day(lead, trading, article.eventTime());
            if (organizations.size() != 1
                    || !extractor.extractOrganizations(clause.substring(0, subject.end()), null).equals(organizations)
                    || up == down || day == null || FORECAST.matcher(movement).find()) {
                continue;
            }
            profiles.put(article.articleId(), new Profile(organizations.iterator().next(), venue(trading.group(4)),
                    day, up, article.eventTime()));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return sameInstrument(a, b) && a.day().equals(b.day()) && a.up() == b.up()
                && a.time() != null && b.time() != null
                && Duration.between(a.time(), b.time()).abs().compareTo(Duration.ofHours(48)) <= 0;
    }

    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return sameInstrument(a, b) && (!a.day().equals(b.day()) || a.up() != b.up());
    }

    private static boolean sameInstrument(Profile a, Profile b) {
        return a != null && b != null && a.organization().equals(b.organization()) && a.venue().equals(b.venue());
    }

    private static String venue(String name) {
        if (name.startsWith("뉴욕") || name.startsWith("미국")) {
            return "US";
        }
        if (name.equals("코스닥")) {
            return "KOSDAQ";
        }
        return "KOSPI";
    }

    private static LocalDate day(String text, Matcher matcher, OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        String preceding = text.substring(Math.max(0, matcher.start() - 6), matcher.start());
        if (preceding.matches(".*(?:지난달|다음달|지난해|작년).*")) {
            return null;
        }
        try {
            LocalDate result = LocalDate.of(matcher.group(1) == null ? time.getYear() : Integer.parseInt(matcher.group(1)),
                    matcher.group(2) == null ? time.getMonthValue() : Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            return result.isAfter(time.toLocalDate()) ? null : result;
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static String bound(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private record Profile(String organization, String venue, LocalDate day, boolean up, OffsetDateTime time) {}
}
