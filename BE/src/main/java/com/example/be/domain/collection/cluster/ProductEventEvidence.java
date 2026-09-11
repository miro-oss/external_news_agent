package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.time.DateTimeException;
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

/** Named product announcements, distinct from forecasts about the surrounding market. */
final class ProductEventEvidence {
    private static final int LEAD_LIMIT = 1200;
    private static final Pattern QUOTED = Pattern.compile("['‘“\"]([^'’”\"\\n]{2,60})['’”\"]");
    private static final Pattern SUBJECT = Pattern.compile(
            "(?<![가-힣a-z0-9])([가-힣a-z][가-힣a-z0-9&.-]{1,30}?)(?:은|는|이|가|도)\\s+");
    private static final Pattern OWNED_PRODUCT = Pattern.compile(
            "(?<![가-힣a-z0-9])([가-힣a-z][가-힣a-z0-9&.-]{1,30})의\\s+"
                    + "['‘“\"]?([^'‘’“”\"\\n.]{2,50}?)['’”\"]?\\s+(?:시리즈|모델|제품)(?:은|는|이|가)?");
    private static final Pattern COMPLETED_REVEAL = Pattern.compile(
            "(?:공개|발표|출시)(?:했다|하였다|했으며|했고|한\\s*것)|선보였다|내놓았다");
    private static final Pattern PRODUCT_CONTEXT = Pattern.compile(
            "신제품|신형|제품|시리즈|모델|스마트폰|폴더블|노트북|태블릿|로봇|반도체|프로세서|칩|자동차|전기차");
    private static final Pattern PREORDER_RESULT = Pattern.compile(
            "(?:사전\\s*예약|사전\\s*판매|예약\\s*판매).{0,20}(?:완판|매진|판매\\s*기록)");
    private static final Pattern INSTALLED = Pattern.compile("탑재됐|탑재되었|장착됐|장착되었|적용됐|적용되었");
    private static final Pattern CHIP = Pattern.compile(
            "([가-힣a-z][가-힣a-z-]{1,20}\\s*[0-9]+[a-z0-9.-]*"
                    + "(?:\\s*(?:프로|울트라|맥스|pro|ultra|max))?)\\s*(?:칩|프로세서)");
    private static final Pattern VERSION = Pattern.compile("^\\s*(?:시리즈\\s*)?v([0-9]+(?:\\.[0-9]+)*)");
    private static final Pattern DAY = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?(?<![0-9])([0-9]{1,2})일");
    private static final Pattern BACKGROUND = Pattern.compile("^(?:한편|앞서|과거|일례로|관련\\s*소식)");
    private static final Pattern CAPTION = Pattern.compile(
            "\\(사진\\s*[=:＝]|사진\\s*[=:＝]|재판매\\s*및\\s*db\\s*금지|copyright"
                    + "|^(?:등록|입력|수정)\\s*20[0-9]{2}[.년/-]");
    private static final Pattern FORECAST = Pattern.compile("전망|예상|관측|내다봤|추산");
    private static final Pattern RESEARCH = Pattern.compile("시장\\s*조사|조사\\s*업체|리서치|연구\\s*기관");
    private static final Pattern MARKET_METRIC = Pattern.compile("점유율|출하량|판매량|시장\\s*규모|수요");
    private static final Map<String, Pattern> CATEGORIES = Map.of(
            "phone", Pattern.compile("스마트폰|폴더블|아이폰|휴대폰"),
            "computer", Pattern.compile("노트북|태블릿|컴퓨터"),
            "vehicle", Pattern.compile("자동차|전기차|승용차"),
            "robot", Pattern.compile("로봇|휴머노이드"),
            "chip", Pattern.compile("반도체|프로세서|칩"));
    private static final Set<String> GENERAL_SUBJECTS = Set.of(
            "업계", "제품", "신제품", "신형", "시리즈", "모델", "회사", "업체", "시장", "화면", "기술", "가격",
            "관계자", "내구성", "사용성", "성능", "이날", "전날", "당일", "올해", "지난해");

    private final Map<Long, Profile> profiles = new HashMap<>();

    ProductEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String rawBody = normalize(ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()));
            String body = foreground(rawBody);
            String summary = foreground(normalize(article.summary()));
            // Some fetched bodies contain only captions while the supplied summary contains the article.
            // Only absent/caption bodies permit that fallback; a substantive lead keeps its own focal event.
            boolean summaryFallback = rawBody.isBlank() || captionOnly(rawBody);
            boolean bodyForecast = marketForecast(body);
            Product product = announcement(body, article.eventTime());
            if (product == null && summaryFallback && !bodyForecast) {
                product = announcement(summary, article.eventTime());
            }
            if (product == null && summaryFallback && !bodyForecast && PREORDER_RESULT.matcher(title).find()) {
                product = metadataProduct(title, summary);
            }
            Set<String> categories = categories(title + "\n" + body + "\n" + summary);
            boolean forecast = bodyForecast
                    || product == null && summaryFallback && marketForecast(summary);
            profiles.put(article.articleId(), new Profile(product, forecast, categories, article.eventTime()));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || a.product() == null || b.product() == null
                || a.time() == null || b.time() == null
                || Duration.between(a.time(), b.time()).abs().compareTo(Duration.ofHours(48)) > 0
                || conflicts(left, right)) {
            return false;
        }
        Product x = a.product();
        Product y = b.product();
        if (!x.maker().equals(y.maker()) || !x.name().equals(y.name())) {
            return false;
        }
        if (x.day() != null && y.day() != null) {
            return x.day().equals(y.day());
        }
        // An undated preorder headline is only a bridge to a dated announcement when the
        // summary also identifies its installed processor. Two sparse snippets cannot bootstrap it.
        return (x.day() != null || y.day() != null) && intersects(x.chips(), y.chips());
    }

    /** Explicit focal scopes may veto other rules; missing product facts remain unknown. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null) {
            return false;
        }
        Product x = a.product();
        Product y = b.product();
        if ((a.forecast() && y != null || b.forecast() && x != null)
                && intersects(a.categories(), b.categories())) {
            return true;
        }
        return x != null && y != null && (!x.maker().equals(y.maker()) || !x.name().equals(y.name())
                || different(x.day(), y.day()) || different(x.version(), y.version()));
    }

    private static Product announcement(String text, OffsetDateTime time) {
        if (marketForecast(text)) {
            return null;
        }
        for (String sentence : text.split("(?<=[.!?])\\s+|\\n+")) {
            Matcher completed = COMPLETED_REVEAL.matcher(sentence);
            while (completed.find()) {
                String preceding = sentence.substring(0, completed.start());
                if (!PRODUCT_CONTEXT.matcher(preceding).find()) {
                    continue;
                }
                Matcher quoted = QUOTED.matcher(preceding);
                String name = null;
                String maker = null;
                String version = null;
                int productEnd = -1;
                while (quoted.find()) {
                    if (!numberedName(quoted.group(1)) || completed.start() - quoted.end() > 200) {
                        continue;
                    }
                    String candidateMaker = maker(preceding.substring(0, quoted.start()));
                    if (candidateMaker != null) {
                        name = compact(quoted.group(1));
                        maker = candidateMaker;
                        productEnd = quoted.end();
                        version = version(preceding.substring(quoted.end()));
                    }
                }
                LocalDate day = eventDay(preceding, time);
                if (name != null && day != null) {
                    int sourceStart = text.indexOf(sentence) + productEnd;
                    String details = text.substring(sourceStart, Math.min(text.length(), sourceStart + 700));
                    return new Product(maker, name, version, day, chips(details));
                }
            }
        }
        return null;
    }

    private static Product metadataProduct(String title, String summary) {
        Matcher owned = OWNED_PRODUCT.matcher(summary);
        if (owned.find() && numberedName(owned.group(2)) && !GENERAL_SUBJECTS.contains(owned.group(1))
                && compact(title).contains(compact(owned.group(1)))) {
            Set<String> chips = chips(summary.substring(owned.end()));
            if (!chips.isEmpty()) {
                return new Product(compact(owned.group(1)), compact(owned.group(2)), null, null, chips);
            }
        }
        return null;
    }

    private static String maker(String beforeProduct) {
        Matcher subject = SUBJECT.matcher(beforeProduct);
        String result = null;
        while (subject.find()) {
            if (!GENERAL_SUBJECTS.contains(subject.group(1)) && beforeProduct.length() - subject.end() <= 180) {
                result = compact(subject.group(1));
            }
        }
        return result;
    }

    private static Set<String> chips(String details) {
        Set<String> result = new HashSet<>();
        for (String sentence : details.split("(?<=[.!?])\\s+|\\n+")) {
            if (!INSTALLED.matcher(sentence).find()) {
                continue;
            }
            Matcher quoted = QUOTED.matcher(sentence);
            while (quoted.find()) {
                String after = sentence.substring(quoted.end());
                if (numberedName(quoted.group(1)) && INSTALLED.matcher(bound(after, 35)).find()) {
                    result.add(compact(quoted.group(1)));
                }
            }
            Matcher chip = CHIP.matcher(sentence);
            while (chip.find()) {
                if (INSTALLED.matcher(bound(sentence.substring(chip.end()), 35)).find()) {
                    result.add(compact(chip.group(1)));
                }
            }
        }
        return result;
    }

    private static LocalDate eventDay(String context, OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        Matcher day = DAY.matcher(context);
        LocalDate explicit = null;
        int lastExplicitEnd = -1;
        while (day.find()) {
            try {
                String beforeDay = context.substring(Math.max(0, day.start() - 8), day.start());
                LocalDate reference = time.toLocalDate();
                if (beforeDay.matches(".*(?:지난달|전월)\\s*$")) {
                    reference = reference.minusMonths(1);
                } else if (beforeDay.matches(".*(?:다음달|다음\\s*달)\\s*$")) {
                    reference = reference.plusMonths(1);
                } else if (beforeDay.matches(".*(?:지난해|작년)\\s*$")) {
                    reference = reference.minusYears(1);
                } else if (beforeDay.matches(".*내년\\s*$")) {
                    reference = reference.plusYears(1);
                }
                int year = day.group(1) == null ? reference.getYear() : Integer.parseInt(day.group(1));
                int month = day.group(2) == null ? reference.getMonthValue() : Integer.parseInt(day.group(2));
                LocalDate candidate = LocalDate.of(year, month, Integer.parseInt(day.group(3)));
                if (candidate.isAfter(time.toLocalDate())) {
                    return null;
                }
                explicit = candidate;
                lastExplicitEnd = day.end();
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        if (context.contains("전날") || context.contains("어제")) {
            LocalDate previousDay = time.toLocalDate().minusDays(1);
            if (explicit == null) {
                return previousDay;
            }
            int relativePosition = Math.max(context.lastIndexOf("전날"), context.lastIndexOf("어제"));
            if (relativePosition < lastExplicitEnd) {
                // "전날인 11일" names the event date itself; never subtract a second time.
                return explicit.equals(previousDay) ? explicit : null;
            }
            // "12일 업계에 따르면 … 전날" states today's reporting date first.
            // A different explicit date with a trailing relative date is ambiguous.
            return explicit.equals(time.toLocalDate()) ? previousDay : null;
        }
        if (explicit != null) {
            return explicit;
        }
        return context.contains("이날") || context.contains("오늘") ? time.toLocalDate() : null;
    }

    private static boolean marketForecast(String text) {
        String lead = bound(text, 650);
        String opening = lead.split("(?<=[.!?])\\s+|\\n+", 2)[0];
        return lead.contains("시장") && FORECAST.matcher(bound(opening, 300)).find()
                && MARKET_METRIC.matcher(lead).find() && RESEARCH.matcher(lead).find();
    }

    private static boolean captionOnly(String body) {
        for (String paragraph : bound(body, LEAD_LIMIT).split("\\n\\s*\\n")) {
            if (!paragraph.isBlank() && !CAPTION.matcher(paragraph.strip()).find()) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> categories(String text) {
        Set<String> result = new HashSet<>();
        CATEGORIES.forEach((category, pattern) -> {
            if (pattern.matcher(text).find()) {
                result.add(category);
            }
        });
        return result;
    }

    private static boolean numberedName(String value) {
        return value.matches("[가-힣a-z0-9 ._-]{2,60}") && value.matches(".*[0-9].*")
                && value.matches(".*[가-힣a-z].*") && value.trim().split("\\s+").length <= 6
                && !value.matches(".*(?:[0-9]년|[0-9]월|[0-9]일|[0-9]만원|[0-9]위안).*");
    }

    private static String version(String value) {
        Matcher version = VERSION.matcher(value);
        return version.find() ? version.group(1) : null;
    }

    private static String foreground(String text) {
        StringBuilder result = new StringBuilder();
        for (String sentence : bound(text, LEAD_LIMIT).split("(?<=[.!?])\\s+|\\n+")) {
            if (BACKGROUND.matcher(sentence.strip()).find()) {
                break;
            }
            result.append(sentence).append('\n');
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return value.replaceAll("[^가-힣a-z0-9]", "");
    }

    private static String bound(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private static boolean intersects(Set<String> first, Set<String> second) {
        return first.stream().anyMatch(second::contains);
    }

    private static boolean different(Object first, Object second) {
        return first != null && second != null && !first.equals(second);
    }

    private record Product(String maker, String name, String version, LocalDate day, Set<String> chips) {}
    private record Profile(Product product, boolean forecast, Set<String> categories, OffsetDateTime time) {}
}
