package com.example.be.domain.collection.cluster;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
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

/** Institution, named occasion and explicit event period; publication time is never an occasion. */
final class InstitutionalEventEvidence {
    private static final int LEAD_LIMIT = 700;
    private static final Pattern BACKGROUND = Pattern.compile(
            "(?:^|[.!?]\\s+|\\n+)(?:한편|앞서|과거|일례로|관련\\s*소식)\\s*");
    private static final Pattern INSTITUTION = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z가-힣]{2,25}?(?:대학교|대학|연구원|연구소|진흥원|교육원|공단|대))"
                    + "(?=(?:은|는|이|가|의|에서|와|과)?(?:[\\s,，.('‘“]|$))");
    private static final Set<String> GENERAL_INSTITUTIONS = Set.of(
            "대학교", "전문대", "전문대학", "사립대", "국립대", "수도권대", "교육대", "연구원");
    private static final String OCCASION_SUFFIX = "(?:교육\\s*프로그램|실습\\s*교육|교육\\s*과정|실습|교육|연수|캠프|포럼|세미나|워크숍|강좌|설명회|박람회|아카데미|특강)";
    private static final Pattern OCCASION = Pattern.compile(OCCASION_SUFFIX);
    private static final Pattern QUOTED_OCCASION = Pattern.compile(
            "['‘“\"]([^'’”\"\\n]{3,100}" + OCCASION_SUFFIX + ")['’”\"]");
    private static final Pattern UNQUOTED_OCCASION = Pattern.compile(
            "([a-z가-힣0-9-]+(?:\\s+[a-z가-힣0-9-]+){0,5}\\s*" + OCCASION_SUFFIX + ")");
    private static final Pattern RUN_ACTION = Pattern.compile(
            "실시|진행|운영|개최|개설|열었|열린|열리는|열고|마쳤|마무리|수료|성료");
    private static final Pattern OTHER_FOCUS = Pattern.compile("투자|협약|등록금|인사|총장|신축|건립|예산|지원사업|선정");
    private static final Pattern ORDINAL = Pattern.compile("제?\\s*([0-9]+)\\s*(기|회|차)(?![가-힣])");
    // The first month is mandatory. Bare days and inferred publication dates are insufficient.
    private static final Pattern RANGE = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?([0-9]{1,2})월\\s*([0-9]{1,2})일?\\s*(?:부터|[~∼〜–—-])\\s*"
                    + "(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?([0-9]{1,2})일(?:까지)?");
    private static final Pattern DAY = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?([0-9]{1,2})월\\s*([0-9]{1,2})일");
    private static final Pattern RELATIVE_YEAR = Pattern.compile("지난해|작년|재작년|내년|내후년");
    private static final Pattern ADMISSION_FOCUS = Pattern.compile("경쟁률|모집\\s*결과|지원자|지원\\s*인원");
    private static final Pattern ADMISSION_COUNTS = Pattern.compile(
            "([0-9][0-9,]*)\\s*명\\s*모집(?:에|,)?\\s*([0-9][0-9,]*)\\s*명\\s*(?:지원|몰려)");
    private static final Pattern ACADEMIC_YEAR = Pattern.compile("(20[0-9]{2})\\s*학년도");
    private static final Pattern ADMISSION_PHASE = Pattern.compile("수시|정시");
    private static final Pattern ADMISSION_SUBGROUP = Pattern.compile(
            "학과|학부|전공|대학원|단과대|의예과|치의예과|한의예과|수의예과|전형|모집\\s*단위|학생부|논술|정원\\s*외");
    private static final Pattern HISTORICAL_RESULT = Pattern.compile(
            "지난해|작년|재작년|전년|당시|종전|기존|과거|지난\\s*(?:학년도|20[0-9]{2})");
    private static final Pattern RATE = Pattern.compile("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(?:대|:)\\s*1(?![0-9])");
    private static final Pattern OVERALL_RATE = Pattern.compile(
            "(?:전체|최종|평균)\\s*(?:경쟁률(?:은|는|이|가)?\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*(?:대|:)\\s*1(?![0-9])"
                    + "|경쟁률(?:은|는|이|가)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:대|:)\\s*1(?![0-9])");

    private final Map<Long, Profile> profiles = new HashMap<>();

    InstitutionalEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String lead = foreground(normalize(bound(ArticleEvidenceText.primary(article), LEAD_LIMIT)));
            profiles.put(article.articleId(), new Profile(
                    occasion(title, lead, article.eventTime()), admission(title, lead)));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || conflicts(left, right)) {
            return false;
        }
        Occasion x = a.occasion();
        Occasion y = b.occasion();
        if (x != null && y != null && x.institution().equals(y.institution())
                && x.name().equals(y.name()) && x.period() != null && x.period().equals(y.period())
                && !different(x.ordinal(), y.ordinal())) {
            return true;
        }
        Admission p = a.admission();
        Admission q = b.admission();
        return p != null && q != null && p.institution().equals(q.institution())
                && sameKnown(p.year(), q.year()) && sameKnown(p.phase(), q.phase())
                && sameKnown(p.rate(), q.rate());
    }

    /** A missing event date or admission result is unknown, never a disagreement. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null) {
            return false;
        }
        Occasion x = a.occasion();
        Occasion y = b.occasion();
        if (x != null && y != null && x.name().equals(y.name())
                && (!x.institution().equals(y.institution()) || different(x.ordinal(), y.ordinal())
                || different(x.period(), y.period()))) {
            return true;
        }
        Admission p = a.admission();
        Admission q = b.admission();
        return p != null && q != null && (!p.institution().equals(q.institution())
                || different(p.year(), q.year()) || different(p.phase(), q.phase()) || different(p.rate(), q.rate()));
    }

    private static Occasion occasion(String title, String lead, OffsetDateTime publication) {
        if (!OCCASION.matcher(title).find()
                || (OTHER_FOCUS.matcher(title).find() && !RUN_ACTION.matcher(title).find())) {
            return null;
        }
        String institution = institution(title, lead);
        if (institution == null) {
            return null;
        }
        Set<String> names = quotedNames(title);
        Set<String> leadNames = quotedNames(lead);
        if (!names.isEmpty() && !leadNames.isEmpty()) {
            names.retainAll(leadNames);
        } else if (names.isEmpty()) {
            names.addAll(leadNames);
        }
        if (names.isEmpty()) {
            String withoutInstitution = INSTITUTION.matcher(title).replaceAll(match ->
                            institution.equals(DeterministicEntityExtractor.canonicalSubject(match.group(1))) ? " " : match.group())
                    .replaceAll("제?\\s*[0-9]+\\s*(?:기|회|차)", " ");
            Matcher unquoted = UNQUOTED_OCCASION.matcher(withoutInstitution);
            while (unquoted.find()) {
                String name = occasionName(unquoted.group(1));
                if (distinctive(name) && compact(lead).contains(name)) {
                    names.add(name);
                }
            }
        }
        // Several separate named programs in a summary must not form a combined identity.
        if (names.size() != 1 || leadNames.size() > 1) {
            return null;
        }
        String name = names.iterator().next();
        if (!compact(lead).contains(name)) {
            return null;
        }
        Set<Period> periods = new HashSet<>();
        boolean ambiguousPeriod = false;
        for (String sentence : lead.split("(?<=[.!?])\\s+|\\n+")) {
            if (compact(sentence).contains(name) && RUN_ACTION.matcher(sentence).find()) {
                Period period = period(sentence, name, publication);
                if (period != null) {
                    periods.add(period);
                }
                ambiguousPeriod |= period == null && (RANGE.matcher(sentence).find() || DAY.matcher(sentence).find());
            }
        }
        Period knownPeriod = periods.size() == 1 ? periods.iterator().next() : null;
        // Invalid/ambiguous dates in a second event sentence must not be silently discarded.
        if (periods.size() > 1 || ambiguousPeriod) {
            knownPeriod = null;
        }
        return new Occasion(institution, name, oneGroup(ORDINAL, title + "\n" + lead, 0), knownPeriod);
    }

    private static Admission admission(String title, String lead) {
        Matcher headlineCounts = ADMISSION_COUNTS.matcher(title);
        boolean countedResult = headlineCounts.find();
        if ((!ADMISSION_FOCUS.matcher(title).find() && !countedResult)
                || !ADMISSION_PHASE.matcher(title + "\n" + lead).find() || !currentOverallContext(title)) {
            return null;
        }
        String institution = institution(title, lead);
        if (institution == null) {
            return null;
        }
        // Admission facts must be in the opening; a later historical comparison is not this result.
        String primary = title + "\n" + bound(lead, 350);
        String rate = oneGroup(RATE, title, 1);
        if (rate == null) {
            Set<String> rates = new HashSet<>();
            for (String sentence : bound(lead, 350).split("(?<=[.!?])\\s+|\\n+")) {
                // "학과 경쟁률" and "지난해 평균 경쟁률" are both explicitly
                // scoped rates, even when the word 경쟁률 immediately precedes the number.
                if (!currentOverallContext(sentence)) {
                    continue;
                }
                Matcher matcher = OVERALL_RATE.matcher(sentence);
                while (matcher.find()) {
                    rates.add(decimal(matcher.group(1) == null ? matcher.group(2) : matcher.group(1)));
                }
            }
            rate = rates.size() == 1 ? rates.iterator().next() : null;
        } else {
            rate = decimal(rate);
        }
        if (countedResult) {
            BigDecimal seats = new BigDecimal(headlineCounts.group(1).replace(",", ""));
            BigDecimal applicants = new BigDecimal(headlineCounts.group(2).replace(",", ""));
            // Counts identify the same admissions result only when their exact ratio
            // agrees with the explicit overall rate. Do not infer a missing rate.
            if (rate == null || seats.signum() <= 0
                    || new BigDecimal(rate).multiply(seats).compareTo(applicants) != 0 || headlineCounts.find()) {
                return null;
            }
        }
        String phase = oneGroup(ADMISSION_PHASE, primary, 0);
        String ordinal = oneGroup(ORDINAL, primary, 0);
        if (phase != null && ordinal != null) {
            phase += ":" + ordinal;
        }
        return new Admission(institution, oneGroup(ACADEMIC_YEAR, primary, 1), phase, rate);
    }

    private static boolean currentOverallContext(String text) {
        // The entire 수시/정시 phase is supported. A narrower named 전형 is not
        // interchangeable with the university's overall result.
        String context = text.replaceAll("(?:수시|정시)(?:\\s*모집)?\\s*전형", " ");
        return !ADMISSION_SUBGROUP.matcher(context).find() && !HISTORICAL_RESULT.matcher(context).find();
    }

    private static String institution(String title, String lead) {
        Set<String> titleInstitutions = institutions(title);
        Set<String> leadInstitutions = institutions(bound(lead, 350));
        // Both the headline subject and introductory institution must identify the same actor.
        if (titleInstitutions.size() != 1 || leadInstitutions.size() != 1 || !titleInstitutions.equals(leadInstitutions)) {
            return null;
        }
        return titleInstitutions.iterator().next();
    }

    private static Set<String> institutions(String text) {
        Set<String> result = new HashSet<>(DeterministicEntityExtractor.institutionSubjects(text));
        Matcher matcher = INSTITUTION.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            // The shared extractor owns university recognition and alias handling.
            if (!name.matches(".*(?:대학교|대학|대)$") && !GENERAL_INSTITUTIONS.contains(name)) {
                result.add(DeterministicEntityExtractor.canonicalSubject(name));
            }
        }
        return result;
    }

    private static Set<String> quotedNames(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = QUOTED_OCCASION.matcher(text);
        while (matcher.find()) {
            String name = occasionName(matcher.group(1));
            if (distinctive(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private static String occasionName(String text) {
        return compact(ORDINAL.matcher(text).replaceAll(" "));
    }

    private static boolean distinctive(String name) {
        String specific = name.replaceAll(OCCASION_SUFFIX, "").replaceAll("[0-9]|제|하계|동계|여름|겨울", "");
        return specific.length() >= 3;
    }

    private static Period period(String sentence, String name, OffsetDateTime publication) {
        // Relative years need a separately explicit year; never silently turn last year into this year.
        if (RELATIVE_YEAR.matcher(sentence).find()) {
            return null;
        }
        Matcher range = RANGE.matcher(sentence);
        Set<Period> found = new HashSet<>();
        boolean sawRange = false;
        while (range.find()) {
            sawRange = true;
            if (!attachedToName(sentence, name, range.start(), range.end())) {
                return null;
            }
            Integer year = year(range.group(1), publication);
            Integer endYear = range.group(4) == null ? year : Integer.valueOf(range.group(4));
            Integer month = Integer.valueOf(range.group(2));
            LocalDate start = date(year, month, range.group(3));
            LocalDate end = date(endYear, range.group(5) == null ? month : Integer.valueOf(range.group(5)), range.group(6));
            if (start == null || end == null || end.isBefore(start)) {
                return null;
            }
            found.add(new Period(start, end));
        }
        if (sawRange) {
            return found.size() == 1 ? found.iterator().next() : null;
        }
        if (sentence.matches(".*(?:발표|밝혔|전했|보도|설명|소개|알렸).*")) {
            return null;
        }
        Matcher day = DAY.matcher(sentence);
        while (day.find()) {
            if (!attachedToName(sentence, name, day.start(), day.end())) {
                return null;
            }
            // A date followed by a reporting verb identifies the announcement, not the occasion.
            String after = sentence.substring(day.end());
            Matcher action = RUN_ACTION.matcher(after);
            if (!action.find() || after.substring(0, action.start()).matches(".*(?:발표|밝혔|전했|보도).*")) {
                continue;
            }
            LocalDate date = date(year(day.group(1), publication), Integer.valueOf(day.group(2)), day.group(3));
            if (date == null) {
                return null;
            }
            found.add(new Period(date, date));
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static boolean attachedToName(String sentence, String name, int dateStart, int dateEnd) {
        String normalized = compact(sentence);
        int nameStart = normalized.indexOf(name);
        int nameEnd = nameStart + name.length();
        int start = compact(sentence.substring(0, dateStart)).length();
        int end = compact(sentence.substring(0, dateEnd)).length();
        if (nameStart < 0) {
            return false;
        }
        String gap = end <= nameStart ? normalized.substring(end, nameStart)
                : nameEnd <= start ? normalized.substring(nameEnd, start) : "";
        // A different action between the date and program owns that date. Shared
        // sentences alone are insufficient (e.g. recruitment dates before a training report).
        return gap.length() <= 80 && !gap.matches(".*(?:접수|모집|신청|채용|발표|밝혔|보도|공사|준공|착공|했고|했으며|하고|하며).*");
    }

    private static Integer year(String explicit, OffsetDateTime publication) {
        return explicit != null ? Integer.valueOf(explicit) : publication == null ? null : publication.getYear();
    }

    private static LocalDate date(Integer year, Integer month, String day) {
        try {
            return year == null ? null : LocalDate.of(year, month, Integer.parseInt(day));
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static String oneGroup(Pattern pattern, String text, int group) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(compact(matcher.group(group)).replaceFirst("^제", ""));
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static String decimal(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    private static <T> boolean sameKnown(T left, T right) {
        return left != null && left.equals(right);
    }

    private static <T> boolean different(T left, T right) {
        return left != null && right != null && !left.equals(right);
    }

    private static String foreground(String value) {
        Matcher background = BACKGROUND.matcher(value);
        return background.find() ? value.substring(0, background.start()) : value;
    }

    private static String compact(String value) {
        return value.replaceAll("[^a-z0-9가-힣.]", "");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(nullToEmpty(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String bound(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Period(LocalDate start, LocalDate end) {}
    private record Occasion(String institution, String name, String ordinal, Period period) {}
    private record Admission(String institution, String year, String phase, String rate) {}
    private record Profile(Occasion occasion, Admission admission) {}
}
