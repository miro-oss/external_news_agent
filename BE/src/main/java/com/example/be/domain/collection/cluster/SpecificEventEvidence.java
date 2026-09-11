package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative event identities from headline subjects and bounded, explicitly dated facts. */
final class SpecificEventEvidence {
    private static final int LEAD_LIMIT = 1200;
    private static final Pattern ECONOMY = Pattern.compile("gdp|gni|국내총생산|국민총소득|국민소득");
    private static final Pattern INDICATOR = Pattern.compile(
            "(?:명목|실질)?\\s*(?:국내총생산(?:\\s*\\(gdp\\))?|국민총소득(?:\\s*\\(gni\\))?|gdp|gni)"
                    + "|수출\\s*디플레이터|gdp\\s*디플레이터|민간소비|지식재산생산물투자|수출|수입");
    private static final Pattern RATE = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?\\s*%");
    private static final Pattern QUARTER = Pattern.compile("(?:(20[0-9]{2})년\\s*)?([1-4])\\s*분기");
    private static final Pattern EDITION = Pattern.compile("잠정|속보|확정|개정|수정");
    private static final Pattern AGENCY = Pattern.compile("한국은행|한은|[가-힣]{2,}통계청|통계청|[가-힣]{2,}중앙은행");
    private static final Pattern RECORD_YEARS = Pattern.compile("([0-9]+)\\s*년\\s*만");
    private static final Pattern DAY = Pattern.compile("(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?(?<![0-9])([0-9]{1,2})일");
    private static final Pattern INDEX = Pattern.compile("코스피|코스닥|나스닥|닛케이|다우지수");
    private static final Pattern MARKET = Pattern.compile("상승|강세|급등|하락|약세|급락|탈환|돌파|재등정|출발|장중|회복|되찾");
    private static final Pattern INDEX_OPEN = Pattern.compile("([0-9]{3,}(?:\\.[0-9]+)?)\\s*(?:포인트|p)?(?:으로|로|에)?\\s*출발");
    private static final Pattern ETF = Pattern.compile("etf|리밸런싱|비중\\s*(?:조절|조정)|정기\\s*변경");
    private static final Pattern PROJECT_NAME = Pattern.compile("([가-힣a-z][가-힣a-z0-9-]{1,})\\s+(?:발전소|프로젝트)");
    private static final Pattern PLANT_LOCATION = Pattern.compile("([가-힣a-z][가-힣a-z0-9-]{2,}?)(?:에|에서)\\s*(?:가스|복합|화력)");
    private static final Pattern PLANT = Pattern.compile("발전소|발전\\s*설비");
    private static final Pattern PENDING = Pattern.compile("검토|협의|유력|추진|떠오|계획|제안|논의");
    private static final Pattern COMPLETE_STAGE = Pattern.compile("착공|준공|상업\\s*가동|가동\\s*개시|계약\\s*체결|수주\\s*확정");
    private static final Pattern CAPACITY = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(gw|mw)");
    private static final Pattern ROBOT = Pattern.compile("휴머노이드|로봇");
    private static final Pattern DEMONSTRATION = Pattern.compile("시연|시범|선보|전신\\s*동작");
    private static final Pattern EVENT_NAME = Pattern.compile("['‘“]([^'’”\\n]{4,100}(?:포럼|박람회|전시회|엑스포|컨퍼런스))['’”]");
    private static final Pattern PRODUCT_ALIAS = Pattern.compile("([가-힣]{3,})\\s*\\(([a-z][a-z0-9-]{2,})\\)");
    private static final Pattern QUOTED_PRODUCT = Pattern.compile("(?:로봇|휴머노이드)\\s*['‘“]([가-힣a-z][가-힣a-z0-9-]{2,})(?:\\s+v[0-9.]+)?['’”]");
    private static final Pattern VERSION = Pattern.compile("(?<![a-z0-9])v([0-9]+(?:\\.[0-9]+)+)");
    private static final Pattern INSTITUTE = Pattern.compile("[가-힣]{2,}(?:연구원|연구소)");
    private static final Pattern BACKGROUND_START = Pattern.compile("^(?:한편|앞서|과거|일례로|관련\\s*소식)");
    // Consume longer country names first so Indonesia does not also identify India.
    private static final Pattern COUNTRIES = Pattern.compile("인도네시아|프랑스|캐나다|한국|미국|중국|일본|독일|영국|호주|인도");
    private static final Set<String> POLICY_DOMAINS = Set.of("방산", "군사정보", "안보", "반도체", "인공지능", "양자", "우주", "원자력", "교역");
    private static final Set<String> PROJECT_GENERAL = Set.of("발전소", "발전설비", "대미투자", "대미", "투자", "규모", "건설사", "국내", "참여", "주목", "유력", "추진", "계획", "프로젝트", "사업", "전력", "가스터빈", "복합화력", "가스복합화력", "후보", "정부", "건설", "동부", "서부", "남부", "북부");

    private final Map<Long, Profile> profiles = new HashMap<>();

    SpecificEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()));
            String summary = foreground(bounded(normalize(article.summary()), 500));
            String lead = foreground(bounded(body.isBlank() ? summary : body, LEAD_LIMIT));
            String text = title + "\n" + lead + "\n" + summary;
            String primary = title + "\n" + bounded(lead, 350);
            OffsetDateTime time = article.eventTime();
            profiles.put(article.articleId(), new Profile(title, lead, text, primary, time,
                    statistics(title, text, primary, time), market(title, lead, time),
                    project(title, lead, text), demonstration(title, lead, time)));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || a.time() == null || b.time() == null
                || Duration.between(a.time(), b.time()).abs().compareTo(Duration.ofHours(48)) > 0
                || conflicts(left, right)) {
            return false;
        }
        return sameStatistics(a.statistics(), b.statistics()) || sameMarket(a.market(), b.market())
                || sameSummit(a, b) || sameProject(a.project(), b.project())
                || sameDemonstration(a.demonstration(), b.demonstration());
    }

    /** Missing facts are unknown; only explicit incompatible identities veto other edge rules. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null) {
            return false;
        }
        if (a.primary().contains("정상회담") && b.primary().contains("정상회담")
                && summitOverview(a) && summitOverview(b)
                && (!countries(a.title()).equals(countries(b.title()))
                || different(day(a.lead(), a.time()), day(b.lead(), b.time())))) {
            return true;
        }
        Statistics x = a.statistics();
        Statistics y = b.statistics();
        if (x != null && y != null && (different(x.quarter(), y.quarter())
                || different(x.edition(), y.edition()) || disjointKnown(x.countries(), y.countries())
                || different(x.agency(), y.agency()))) {
            return true;
        }
        Market m = a.market();
        Market n = b.market();
        if (m != null && n != null && m.index().equals(n.index()) && different(m.day(), n.day())) {
            return true;
        }
        Project p = a.project();
        Project q = b.project();
        if (p != null && q != null && (disjointKnown(p.names(), q.names())
                || (sharedProject(p, q) && different(p.stage(), q.stage())))) {
            return true;
        }
        Demonstration d = a.demonstration();
        Demonstration e = b.demonstration();
        return d != null && e != null && !intersection(d.events(), e.events()).isEmpty()
                && (different(d.day(), e.day()) || disjointKnown(d.institutes(), e.institutes())
                || (!intersection(d.products(), e.products()).isEmpty() && different(d.version(), e.version())));
    }

    private static Statistics statistics(String title, String text, String primary, OffsetDateTime time) {
        if (!ECONOMY.matcher(title).find()) {
            return null;
        }
        Matcher period = QUARTER.matcher(primary);
        String quarter = null;
        if (period.find()) {
            String year = period.group(1);
            if (year == null && time != null) {
                String preceding = primary.substring(Math.max(0, period.start() - 8), period.start());
                int relativeYear = preceding.matches(".*재작년\\s*$") ? -2
                        : preceding.matches(".*(?:지난해|작년)\\s*$") ? -1
                        : preceding.matches(".*내년\\s*$") ? 1 : 0;
                year = String.valueOf(time.getYear() + relativeYear);
            }
            quarter = year == null ? null : year + "Q" + period.group(2);
        }
        String agency = first(AGENCY, text);
        if ("한은".equals(agency)) {
            agency = "한국은행";
        }
        return new Statistics(quarter, first(EDITION, primary), agency, countries(primary),
                indicatorRates(text), groups(RECORD_YEARS, title, 1));
    }

    private static boolean sameStatistics(Statistics a, Statistics b) {
        if (a == null || b == null) {
            return false;
        }
        int numbers = intersection(a.indicators(), b.indicators()).size();
        boolean identifiedRelease = a.agency() != null || b.agency() != null;
        if (a.quarter() != null && a.quarter().equals(b.quarter()) && identifiedRelease && numbers >= 2) {
            return true;
        }
        // A metadata-only commentary may omit the quarter and issuer. A named statistic,
        // a matching record interval and explicit domestic scope provide independent clues.
        return numbers >= 1 && identifiedRelease && !intersection(a.recordYears(), b.recordYears()).isEmpty()
                && !intersection(a.countries(), b.countries()).isEmpty();
    }

    private static Set<String> indicatorRates(String text) {
        Set<String> result = new HashSet<>();
        Matcher indicator = INDICATOR.matcher(text);
        while (indicator.find()) {
            int end = Math.min(text.length(), indicator.end() + 75);
            String following = text.substring(indicator.end(), end);
            Matcher next = INDICATOR.matcher(following);
            if (next.find()) {
                following = following.substring(0, next.start());
            }
            int newline = following.indexOf('\n');
            if (newline >= 0) {
                following = following.substring(0, newline);
            }
            Matcher rate = RATE.matcher(following);
            if (rate.find()) {
                String name = compact(indicator.group()).replace("국내총생산gdp", "gdp")
                        .replace("국민총소득gni", "gni").replace("국내총생산", "gdp").replace("국민총소득", "gni")
                        .replace("실질", "");
                result.add(name + ":" + rate.group().replaceAll("\\s", ""));
            }
        }
        return result;
    }

    private static Market market(String title, String lead, OffsetDateTime time) {
        if (ETF.matcher(title).find() || !MARKET.matcher(title).find()) {
            return null;
        }
        String index = first(INDEX, bounded(lead, 180));
        if (index == null || !MARKET.matcher(bounded(lead, 250)).find()) {
            return null;
        }
        Set<String> values = groups(INDEX_OPEN, bounded(lead, 600).replaceAll("(?<=\\d),(?=\\d)", ""), 1);
        return new Market(index, day(bounded(lead, 350), time), values);
    }

    private static boolean sameMarket(Market a, Market b) {
        return a != null && b != null && a.index().equals(b.index()) && a.day() != null
                && a.day().equals(b.day()) && !intersection(a.openingValues(), b.openingValues()).isEmpty();
    }

    private static boolean sameSummit(Profile a, Profile b) {
        Set<String> left = countries(a.title());
        Set<String> right = countries(b.title());
        if (!summitOverview(a) || !summitOverview(b) || !left.equals(right)) {
            return false;
        }
        LocalDate first = day(a.lead(), a.time());
        LocalDate second = day(b.lead(), b.time());
        if ((first == null && DAY.matcher(a.lead()).find()) || (second == null && DAY.matcher(b.lead()).find())
                || different(first, second)
                || ((first == null || second == null)
                && !a.time().toLocalDate().equals(b.time().toLocalDate()))) {
            return false;
        }
        return intersection(domains(a.text()), domains(b.text())).size() >= 3;
    }

    private static boolean summitOverview(Profile profile) {
        return profile.primary().contains("정상회담") && countries(profile.title()).size() == 2
                && !individualAgreement(profile.title())
                && profile.title().matches(".*(?:협력|합의|채택|교역).*");
    }

    private static boolean individualAgreement(String title) {
        return title.matches(".*(?:계약|협약|양해각서)\\s*(?:체결|서명).*")
                || (title.matches(".*(?:투자|계약|협약|수주).*")
                && (!new DeterministicEntityExtractor().extractTitleOrganizations(title).isEmpty()
                || title.matches(".*[a-z0-9가-힣]{2,}(?:전자|로보틱스|에너지|건설|그룹|연구원).*")));
    }

    private static Set<String> domains(String text) {
        Set<String> result = new HashSet<>();
        String normalized = text.replaceAll("(?<![a-z])ai(?![a-z])", "인공지능");
        POLICY_DOMAINS.stream().filter(normalized::contains).forEach(result::add);
        return result;
    }

    private static Project project(String title, String lead, String text) {
        if (!PLANT.matcher(title).find()) {
            return null;
        }
        String primary = title + " " + bounded(lead, 450);
        Set<String> names = new HashSet<>(groups(PROJECT_NAME, primary, 1));
        names.addAll(groups(PLANT_LOCATION, primary, 1));
        names.removeAll(PROJECT_GENERAL);
        // A province or city heading is broader than the named installation in its lead.
        names.removeIf(name -> primary.contains(name + "주 ") || primary.contains(name + "시 "));
        String stage = first(COMPLETE_STAGE, title);
        if (stage == null && PENDING.matcher(title + " " + bounded(lead, 180)).find()) {
            stage = "proposal";
        }
        return new Project(names, groups(CAPACITY, text, 0), stage);
    }

    private static boolean sharedProject(Project a, Project b) {
        return !intersection(a.names(), b.names()).isEmpty();
    }

    private static boolean sameProject(Project a, Project b) {
        return a != null && b != null && "proposal".equals(a.stage()) && "proposal".equals(b.stage())
                && sharedProject(a, b) && intersection(a.capacities(), b.capacities()).size() >= 2;
    }

    private static Demonstration demonstration(String title, String lead, OffsetDateTime time) {
        if (!ROBOT.matcher(title).find() || !ROBOT.matcher(bounded(lead, 350)).find()
                || !DEMONSTRATION.matcher(lead).find()) {
            return null;
        }
        Set<String> products = new HashSet<>(groups(QUOTED_PRODUCT, lead, 1));
        products.addAll(groups(PRODUCT_ALIAS, lead, 1));
        products.addAll(groups(PRODUCT_ALIAS, lead, 2));
        return new Demonstration(groups(EVENT_NAME, lead, 1), products,
                day(bounded(lead, 400), time), groups(INSTITUTE, lead, 0), first(VERSION, lead));
    }

    private static boolean sameDemonstration(Demonstration a, Demonstration b) {
        return a != null && b != null && a.day() != null && a.day().equals(b.day())
                && !intersection(a.events(), b.events()).isEmpty()
                && !intersection(a.products(), b.products()).isEmpty()
                && !intersection(a.institutes(), b.institutes()).isEmpty();
    }

    private static LocalDate day(String text, OffsetDateTime time) {
        Matcher matcher = DAY.matcher(text);
        while (matcher.find()) {
            String preceding = text.substring(Math.max(0, matcher.start() - 5), matcher.start());
            if (preceding.matches(".*(?:지난달|다음달|지난해|작년).*")) {
                continue;
            }
            if (time == null && (matcher.group(1) == null || matcher.group(2) == null)) {
                return null;
            }
            try {
                LocalDate result = LocalDate.of(matcher.group(1) == null ? time.getYear() : Integer.parseInt(matcher.group(1)),
                        matcher.group(2) == null ? time.getMonthValue() : Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                // A bare day later than publication may refer to the previous month;
                // do not manufacture an event date from an ambiguous relative reference.
                return matcher.group(2) == null && result.isAfter(time.toLocalDate()) ? null : result;
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Set<String> countries(String text) {
        String expanded = text.replaceAll("(?:韓|한)\\s*[·ㆍ-]?\\s*(?:佛|불|프(?:랑스)?)", "한국 프랑스")
                .replaceAll("(?:韓|한)\\s*[·ㆍ-]?\\s*(?:美|미)(?![가-힣])", "한국 미국")
                .replaceAll("(?:韓|한)\\s*[·ㆍ-]?\\s*(?:日|일)(?![가-힣])", "한국 일본")
                .replace("우리나라", "한국");
        Set<String> result = new HashSet<>();
        Matcher country = COUNTRIES.matcher(expanded);
        while (country.find()) {
            result.add(country.group());
        }
        return result;
    }

    private static String foreground(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            if (BACKGROUND_START.matcher(paragraph.strip()).find()) {
                break;
            }
            paragraphs.add(paragraph);
        }
        return String.join("\n\n", paragraphs);
    }

    private static <T> boolean different(T a, T b) {
        return a != null && b != null && !a.equals(b);
    }

    private static boolean disjointKnown(Set<String> a, Set<String> b) {
        return !a.isEmpty() && !b.isEmpty() && intersection(a, b).isEmpty();
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static Set<String> groups(Pattern pattern, String text, int group) {
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(compact(matcher.group(group)));
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return value.replaceAll("[^a-z0-9가-힣.%]", "");
    }

    private static String bounded(String value, int limit) {
        return value.substring(0, Math.min(limit, value.length()));
    }

    private record Statistics(String quarter, String edition, String agency, Set<String> countries,
                              Set<String> indicators, Set<String> recordYears) {}
    private record Market(String index, LocalDate day, Set<String> openingValues) {}
    private record Project(Set<String> names, Set<String> capacities, String stage) {}
    private record Demonstration(Set<String> events, Set<String> products, LocalDate day,
                                 Set<String> institutes, String version) {}
    private record Profile(String title, String lead, String text, String primary, OffsetDateTime time,
                           Statistics statistics, Market market, Project project, Demonstration demonstration) {}
}
