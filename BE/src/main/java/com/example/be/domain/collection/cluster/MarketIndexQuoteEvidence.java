package com.example.be.domain.collection.cluster;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Named stock-index observations; company prices and other assets are not index quotes. */
final class MarketIndexQuoteEvidence {
    private static final String NUMBER = "[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?:\\s*[만천]\\s*[0-9]*(?:\\.[0-9]+)?)?";
    private static final Pattern VALUE = Pattern.compile("(?<![a-z0-9])(" + NUMBER + ")");
    private static final Pattern RATE = Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*%");
    private static final Pattern UP = Pattern.compile("상승|급등|폭등|오른|올랐|높아|뛰|플러스|↑");
    private static final Pattern DOWN = Pattern.compile("하락|급락|폭락|내린|내렸|낮아|떨어|밀린|마이너스|↓");
    private static final Pattern NON_FINAL = Pattern.compile("장중|한때|장\\s*초반|개장|시간\\s*외|전망|예상|가능성");
    private static final Pattern PAST_OBSERVATION = Pattern.compile("전날|어제|앞서|과거|지난\\s*(?:주|달|해)|이전\\s*거래일");
    private static final Pattern PAST_AFTER_SUBJECT = Pattern.compile("전날(?!\\s*(?:보다|대비))|어제|앞서|과거|지난\\s*(?:주|달|해)|이전\\s*거래일");
    private static final Pattern OTHER_SUBJECT = Pattern.compile("주가|달러|배럴|국채|유가|환율|선물|시장에서|상장사|상장\\s*(?:기업|종목)|운송\\s*지수|etf");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)|\\n");
    private static final Pattern LEVEL_END = Pattern.compile("^\\s*(?:에|으로|로|을|를|이었다|기록|마감|\\(|[,，]?\\s*$)");
    private static final Pattern NON_LEVEL = Pattern.compile("^\\s*(?:%|포인트|bp|년|월|일|개|배|달러|원)");

    private MarketIndexQuoteEvidence() {}

    static Map<Index, Quote> extract(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?<=[0-9]),(?=[0-9])", "");
        List<Mention> mentions = new ArrayList<>();
        for (Index index : Index.values()) {
            Matcher matcher = index.pattern.matcher(normalized);
            while (matcher.find()) {
                mentions.add(new Mention(index, matcher.start(), matcher.end()));
            }
        }
        mentions.sort(java.util.Comparator.comparingInt(Mention::start));
        Map<Index, Quote> quotes = new EnumMap<>(Index.class);
        Set<Index> ambiguous = new HashSet<>();
        for (int i = 0; i < mentions.size(); i++) {
            Mention mention = mentions.get(i);
            int end = Math.min(normalized.length(), mention.end() + 200);
            if (i + 1 < mentions.size()) {
                end = Math.min(end, mentions.get(i + 1).start());
            }
            if (end <= mention.end()) {
                continue;
            }
            int sentenceStart = 0;
            Matcher precedingEnd = SENTENCE_END.matcher(normalized.substring(0, mention.start()));
            while (precedingEnd.find()) {
                sentenceStart = precedingEnd.end();
            }
            String prefix = normalized.substring(sentenceStart, mention.start());
            if (NON_FINAL.matcher(prefix).find() || PAST_OBSERVATION.matcher(prefix).find()) {
                continue;
            }
            String clause = normalized.substring(mention.end(), end);
            Matcher sentence = SENTENCE_END.matcher(clause);
            if (sentence.find()) {
                clause = clause.substring(0, sentence.start());
            }
            Quote quote = quote(clause);
            if (quote == null) {
                continue;
            }
            Quote previous = quotes.putIfAbsent(mention.index(), quote);
            if (previous != null && !previous.equals(quote)) {
                ambiguous.add(mention.index());
            }
        }
        ambiguous.forEach(quotes::remove);
        return Map.copyOf(quotes);
    }

    /** Two independent index returns and at least one exact level anchor a numeric snapshot. */
    static boolean matches(Map<Index, Quote> first, Map<Index, Quote> second) {
        int sameRates = 0;
        boolean sameLevel = false;
        for (var entry : first.entrySet()) {
            Quote other = second.get(entry.getKey());
            if (other == null) {
                continue;
            }
            if (entry.getValue().rate().compareTo(other.rate()) != 0) {
                return false;
            }
            sameRates++;
            sameLevel |= entry.getValue().level().compareTo(other.level()) == 0;
        }
        return sameRates >= 2 && sameLevel;
    }

    private static Quote quote(String clause) {
        if (NON_FINAL.matcher(clause).find() || OTHER_SUBJECT.matcher(clause).find()
                || PAST_AFTER_SUBJECT.matcher(clause).find()) {
            return null;
        }
        List<Amount> levels = new ArrayList<>();
        Matcher value = VALUE.matcher(clause);
        while (value.find()) {
            String tail = clause.substring(value.end());
            if (!NON_LEVEL.matcher(tail).find() && LEVEL_END.matcher(tail).find()) {
                BigDecimal amount = number(value.group(1));
                if (amount != null && amount.signum() > 0) {
                    levels.add(new Amount(amount, value.start(), value.end()));
                }
            }
        }
        if (levels.size() != 1) {
            return null;
        }
        Amount level = levels.getFirst();
        List<BigDecimal> rates = new ArrayList<>();
        Matcher rate = RATE.matcher(clause);
        while (rate.find()) {
            // In a sentence explaining a bond yield or sector return before the index
            // result, only the nearest percentage can qualify the following level.
            if (rate.end() <= level.start() && RATE.matcher(clause.substring(rate.end(), level.start())).find()) {
                continue;
            }
            // Rounded ranges are not exact values; don't turn '0.5% 안팎' into 0.50%.
            if (clause.substring(rate.end()).matches("^\\s*(?:대|안팎|정도|가량|이상|이하|넘|미만).*$")) {
                continue;
            }
            String observation = clause.substring(Math.min(rate.start(), level.start()),
                    Math.min(clause.length(), Math.max(rate.end(), level.end()) + 20));
            boolean up = UP.matcher(observation).find();
            boolean down = DOWN.matcher(observation).find();
            String literal = rate.group(1);
            BigDecimal percentage = new BigDecimal(literal);
            if (up && down || (literal.startsWith("-") && up) || (literal.startsWith("+") && down)) {
                continue;
            }
            if (!literal.startsWith("-") && !literal.startsWith("+")) {
                if (up == down) {
                    continue;
                }
                percentage = down ? percentage.negate() : percentage;
            }
            rates.add(percentage.stripTrailingZeros());
        }
        return rates.size() == 1 ? new Quote(level.value().stripTrailingZeros(), rates.getFirst()) : null;
    }

    private static BigDecimal number(String text) {
        String value = text.replace(",", "").replaceAll("\\s", "");
        try {
            int unit = value.indexOf('만');
            BigDecimal multiplier = BigDecimal.valueOf(10000);
            if (unit < 0) {
                unit = value.indexOf('천');
                multiplier = BigDecimal.valueOf(1000);
            }
            if (unit < 0) {
                return new BigDecimal(value);
            }
            BigDecimal remainder = unit + 1 == value.length() ? BigDecimal.ZERO : new BigDecimal(value.substring(unit + 1));
            return new BigDecimal(value.substring(0, unit)).multiply(multiplier).add(remainder);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    enum Index {
        DOW("다우(?:존스)?(?:30)?(?:\\s*산업평균)?(?:\\s*지수)?"),
        SP500("(?:s\\s*&\\s*p|스탠더드앤드푸어스(?:\\s*\\(s\\s*&\\s*p\\))?)\\s*500(?:\\s*지수)?"),
        NASDAQ100("나스닥\\s*100(?:\\s*지수)?"),
        NASDAQ("나스닥(?!\\s*100)(?:\\s*종합)?(?:\\s*지수)?"),
        SOX("필라델피아\\s*반도체\\s*지수(?:\\s*\\(sox\\))?|(?<![a-z])sox(?![a-z])"),
        KOSPI("코스피(?:\\s*지수)?"),
        KOSDAQ("코스닥(?:\\s*지수)?");

        final Pattern pattern;
        Index(String expression) { pattern = Pattern.compile(expression); }
    }

    record Quote(BigDecimal level, BigDecimal rate) {}
    private record Mention(Index index, int start, int end) {}
    private record Amount(BigDecimal value, int start, int end) {}
}
