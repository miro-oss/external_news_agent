package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An observed stock-index close, identified by its explicitly reported market and trading day. */
final class MarketSessionEventEvidence {
    private static final int CONTEXT_LIMIT = 900;
    private static final Pattern OBSERVATION = Pattern.compile(
            "상승|급등|폭등|강세|오름|오른|하락|급락|폭락|약세|내림|내린|혼조|반등|돌파|마감");
    private static final Pattern MARKET_SECTION = Pattern.compile(
            "\\[\\s*(?:(?:뉴욕|미국|미|美)\\s*증시|코스피|코스닥)\\s*]");
    private static final Pattern OTHER_FOCUS = Pattern.compile(
            "특징주|개별\\s*종목|주가|신제품|출시|인수|합병|공급\\s*계약|지수\\s*편입|리밸런싱"
                    + "|구성\\s*종목|정기\\s*변경|개장|장중|출발|시간\\s*외|선물|전망|예상|회고|재조명");
    private static final Pattern DATE = Pattern.compile(
            "(?<![0-9])(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?([0-9]{1,2})일(?![0-9]|\\s*(?:간|째|동안|연속))");
    private static final String LOCAL_TIME = "(?:\\(\\s*(?:이하\\s*)?(?:현지|미국|미(?:국)?\\s*동부)\\s*(?:시간|시각)\\s*\\))?";
    private static final Pattern DATE_TO_MARKET = Pattern.compile("^\\s*" + LOCAL_TIME + "\\s*[,，]?\\s*");
    private static final Pattern MARKET_TO_DATE = Pattern.compile("(?:은|는|이|가|에서)?\\s*$");
    private static final Pattern RELATIVE_DATE = Pattern.compile(
            "(?:지난\\s*(?:달|해)|다음\\s*(?:달|해)|작년|재작년|내년|전날|어제|그제)\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)|\\n+");
    private static final Pattern CLOSE = Pattern.compile(
            "마감(?:했|됐|한|하며|된)|(?:거래를?|장을?)\\s*(?:마쳤|마친|끝냈|끝낸)");
    private static final Pattern BACKGROUND_CLOSE = Pattern.compile(
            "^(?:앞서|한편|전날|전일에는|어제|지난(?:달|해|주)|과거|이전\\s*거래일)|전망|예상|가능성");
    private static final Pattern OTHER_INSTRUMENT = Pattern.compile("유가|원유|국채|환율|주가|선물");
    private static final Pattern OUTLOOK_TITLE = Pattern.compile(
            "비관론|낙관론|(?:수요|주문|업황|사업|산업|생산|투자|매출|실적|공급).{0,20}(?:전망|영향|진단|우려|둔화|지속|위축)"
                    + "|(?:전망|진단).{0,12}(?:수요|업황|산업|사업)");
    private static final Pattern BUSINESS_ASSESSMENT = Pattern.compile(
            "(?:사업|산업|업황|수요|주문|투자|생산|공급|실적|매출).{0,60}"
                    + "(?:미칠\\s*영향|영향.{0,16}(?:관심|주목)|(?:지속|둔화|위축|감소|회복|확대).{0,16}(?:전망|예상|평가|분석|진단)|가능성.{0,16}(?:평가|분석|진단))");
    private static final Pattern REPORTING_SENTENCE = Pattern.compile("다[.!?](?:\\s|$)");
    private static final Pattern PHOTO_CREDIT = Pattern.compile("[\\[(]\\s*(?:자료\\s*)?(?:사진|이미지|그래픽)\\s*[=:：]");

    private final Map<Long, Profile> profiles = new HashMap<>();
    private final Set<Long> industryOutlooks = new HashSet<>();

    MarketSessionEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            if (!article.hasFullText() || article.eventTime() == null) {
                continue;
            }
            String title = normalize(detector.coreTitle(article.title()));
            String lead = normalize(ArticleEvidenceText.foreground(article.body(), CONTEXT_LIMIT));
            Set<Market> titleMarkets = new HashSet<>();
            for (Market market : Market.values()) {
                if (market.headline.matcher(title).find()) {
                    titleMarkets.add(market);
                }
            }
            // A roundup of different exchanges is not one market's closing session.
            boolean marketHeadline = OBSERVATION.matcher(title).find() || MARKET_SECTION.matcher(title).find();
            if (titleMarkets.size() == 1 && marketHeadline && !OTHER_FOCUS.matcher(title).find()) {
                Market market = titleMarkets.iterator().next();
                LocalDate day = sessionDay(lead, market, article.eventTime());
                if (day != null && observedIndexClose(lead, market, day, article.eventTime())) {
                    profiles.put(article.articleId(), new Profile(market, day, article.eventTime()));
                    continue;
                }
            }
            if (industryOutlook(title, lead) && !containsDatedClose(lead, article.eventTime())) {
                industryOutlooks.add(article.articleId());
            }
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return sameMarket(a, b) && a.day().equals(b.day())
                && Duration.between(a.reportedAt(), b.reportedAt()).abs().compareTo(Duration.ofHours(48)) <= 0;
    }

    /** A close conflicts with a different session or a clearly separate industry-outlook report. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        return (sameMarket(a, b) && !a.day().equals(b.day()))
                || (a != null && industryOutlooks.contains(right))
                || (b != null && industryOutlooks.contains(left));
    }

    private static boolean sameMarket(Profile a, Profile b) {
        return a != null && b != null && a.market() == b.market();
    }

    private static boolean containsDatedClose(String lead, OffsetDateTime time) {
        for (Market market : Market.values()) {
            LocalDate day = sessionDay(lead, market, time);
            if (day != null && observedIndexClose(lead, market, day, time)) {
                return true;
            }
        }
        return false;
    }

    private static boolean industryOutlook(String title, String lead) {
        if (!OUTLOOK_TITLE.matcher(title).find()) {
            return false;
        }
        // Title, page controls and credited photos can precede the opening prose.
        // The first reporting sentence itself must assess business demand or impact;
        // a later outlook paragraph cannot recast a market close as a separate event.
        for (String paragraph : lead.split("\\n+")) {
            String text = paragraph.strip();
            if (text.equals(title) || PHOTO_CREDIT.matcher(text).find()) {
                continue;
            }
            Matcher reporting = REPORTING_SENTENCE.matcher(text);
            if (reporting.find()) {
                return BUSINESS_ASSESSMENT.matcher(text.substring(0, reporting.end())).find();
            }
        }
        return false;
    }

    private static LocalDate sessionDay(String lead, Market market, OffsetDateTime time) {
        Set<LocalDate> days = new HashSet<>();
        Matcher date = DATE.matcher(lead);
        while (date.find()) {
            String before = lead.substring(0, date.start());
            String after = lead.substring(date.end());
            if (!attachedMarket(before, after, market)) {
                continue;
            }
            if (RELATIVE_DATE.matcher(before).find()) {
                return null;
            }
            LocalDate day = explicitDay(date, time);
            // Do not choose a convenient interpretation from contradictory market dates.
            if (day == null) {
                return null;
            }
            days.add(day);
        }
        return days.size() == 1 ? days.iterator().next() : null;
    }

    private static boolean attachedMarket(String before, String after, Market market) {
        Matcher suffix = DATE_TO_MARKET.matcher(after);
        if (suffix.find() && market.venue.matcher(after.substring(suffix.end())).lookingAt()) {
            return true;
        }
        Matcher venue = market.venue.matcher(before);
        while (venue.find()) {
            if (MARKET_TO_DATE.matcher(before.substring(venue.end())).matches()) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate explicitDay(Matcher date, OffsetDateTime time) {
        int day = Integer.parseInt(date.group(3));
        Integer month = date.group(2) == null ? null : Integer.valueOf(date.group(2));
        Integer year = date.group(1) == null ? null : Integer.valueOf(date.group(1));
        // The numeric trading day is mandatory. The reporting calendar only resolves
        // omitted month/year within two days, including month and year boundaries.
        // It can never substitute the publication date for a missing trading date.
        for (int daysAgo = 0; daysAgo <= 2; daysAgo++) {
            LocalDate candidate = time.toLocalDate().minusDays(daysAgo);
            if (candidate.getDayOfMonth() == day && (month == null || candidate.getMonthValue() == month)
                    && (year == null || candidate.getYear() == year)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean observedIndexClose(String lead, Market market, LocalDate day, OffsetDateTime time) {
        for (String sentence : SENTENCE_END.split(lead)) {
            String text = sentence.strip();
            if (BACKGROUND_CLOSE.matcher(text).find() || !consistentCloseDay(text, day, time)) {
                continue;
            }
            Matcher index = market.index.matcher(text);
            while (index.find()) {
                // The closing predicate must belong to a stock index, not a later oil,
                // bond, FX or single-company report elsewhere in the same article.
                String movement = text.substring(index.end());
                Matcher close = CLOSE.matcher(movement);
                if (close.find() && close.start() <= 180
                        && !OTHER_INSTRUMENT.matcher(movement.substring(0, close.start())).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean consistentCloseDay(String sentence, LocalDate day, OffsetDateTime time) {
        Matcher date = DATE.matcher(sentence);
        while (date.find()) {
            if (!day.equals(explicitDay(date, time))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private enum Market {
        US("(?:미국\\s*)?뉴욕\\s*(?:증시|증권거래소|거래소)|(?:미국|미|美)\\s*증시|nyse",
                "(?:미국|미|美|뉴욕)\\s*증시|나스닥|다우|s\\s*&\\s*p\\s*500|필라델피아\\s*반도체\\s*지수",
                "나스닥(?:\\s*(?:종합|100))?(?:\\s*지수)?|다우(?:존스)?(?:30)?(?:\\s*산업평균)?(?:\\s*지수)?"
                        + "|(?:s\\s*&\\s*p|스탠더드앤드푸어스(?:\\s*\\(s\\s*&\\s*p\\))?)\\s*500(?:\\s*지수)?"
                        + "|필라델피아\\s*반도체\\s*지수"),
        KOSPI("코스피(?:\\s*시장)?|유가증권시장", "코스피", "코스피(?:\\s*지수)?"),
        KOSDAQ("코스닥(?:\\s*시장)?", "코스닥", "코스닥(?:\\s*지수)?");

        private final Pattern venue;
        private final Pattern headline;
        private final Pattern index;

        Market(String venue, String headline, String index) {
            this.venue = Pattern.compile(venue);
            this.headline = Pattern.compile(headline);
            this.index = Pattern.compile(index);
        }
    }

    private record Profile(Market market, LocalDate day, OffsetDateTime reportedAt) {}
}
