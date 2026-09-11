package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

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

/** Named participants and dated occasions from the primary report, never shared background. */
final class NamedEventEvidence {
    private static final int LEAD_LIMIT = 600;
    private static final Pattern BACKGROUND = Pattern.compile(
            "(?:^|[.!?]\\s+|\\n+)(?:한편|앞서|과거|일례로|관련\\s*소식)\\s*");
    private static final Pattern TITLE_SUBJECT = Pattern.compile("^([a-z0-9가-힣]{2,30})(?=[,，\\s])");
    private static final Pattern PARTNER = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z0-9가-힣]{3,30})(?:\\s*\\([^)]{0,80}\\))?(?:와|과)(?=[,，\\s])");
    private static final Pattern COLLABORATION = Pattern.compile("손잡|업무\\s*협약|양해각서|제휴|파트너십|공동\\s*개발");
    private static final Pattern PARTNERSHIP_FOCUS = Pattern.compile(
            "손잡|협약|양해각서|제휴|파트너십|공동\\s*개발|시장\\s*(?:공략|진출|확대)|사업\\s*(?:공략|협력|확대)");
    private static final Pattern TECHNICAL = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2,}[A-Z0-9-]*(?![A-Za-z0-9])");
    private static final Set<String> GENERIC_ANCHORS = Set.of(
            "AI", "MOU", "CEO", "CTO", "CFO", "COO", "NEWS", "NEWSIS", "COM", "PHOTO");
    private static final Set<String> GENERAL_SUBJECTS = Set.of(
            "정부", "기업", "회사", "기관", "시장", "업계", "협회", "관계자", "지난", "이번", "관련", "공공기관");
    private static final Pattern COUNCIL = Pattern.compile("(?<![a-z0-9가-힣])([a-z0-9가-힣]{3,30}위원회)");
    private static final Pattern ANNIVERSARY = Pattern.compile(
            "(출범|창립|개원|설립|개교)\\s*([0-9]+)\\s*주년\\s*(성과\\s*보고회|기념식|기념\\s*행사|행사)");
    private static final Pattern DAY = Pattern.compile(
            "(?:(20[0-9]{2})년\\s*)?(?:([0-9]{1,2})월\\s*)?(?<![0-9])([0-9]{1,2})일");
    private static final Pattern RELATIVE_EVENT_TIME = Pattern.compile(
            "전날|어제|그제|내일|전월|지난\\s*(?:주|달|해)|다음\\s*(?:날|주|달|해)|작년|재작년|내년");
    private static final Set<String> GENERAL_TITLE_TERMS = Set.of(
            "국민", "기업", "성과", "만들", "올해", "내년", "이젠", "이제", "지난", "이번", "향후",
            "정책", "전략", "실행", "발표", "강조", "말했다", "밝혔다");

    private final Map<Long, Profile> profiles = new HashMap<>();

    NamedEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String rawTitle = detector.coreTitle(article.title()).replaceFirst("^(?:\\s*\\[[^]]+])*\\s*", "");
            String body = ArticleBodyCleaner.withoutTrailingBoilerplate(article.body());
            String rawLead = foreground(bound(body.isBlank() ? nullToEmpty(article.summary()) : body, LEAD_LIMIT));
            String title = normalize(rawTitle);
            String lead = normalize(rawLead);
            profiles.put(article.articleId(), new Profile(
                    partnership(title, lead, rawTitle + "\n" + rawLead, article.eventTime()),
                    anniversary(title, lead, article.eventTime())));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null || conflicts(left, right)) {
            return false;
        }
        Partnership p = a.partnership();
        Partnership q = b.partnership();
        if (sameSubject(p, q) && p.partners().equals(q.partners())
                && sameDay(p.day(), q.day()) && intersection(p.technicalScope(), q.technicalScope()).size() >= 2) {
            return true;
        }
        Anniversary x = a.anniversary();
        Anniversary y = b.anniversary();
        return sameInstitution(x, y) && x.occasion().equals(y.occasion())
                && x.ordinal().equals(y.ordinal()) && x.action().equals(y.action()) && sameDay(x.day(), y.day());
    }

    /** Unknown identities do not veto; explicit different partners, dates or editions do. */
    boolean conflicts(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (a == null || b == null) {
            return false;
        }
        Partnership p = a.partnership();
        Partnership q = b.partnership();
        if (sameSubject(p, q) && (intersection(p.partners(), q.partners()).isEmpty() || different(p.day(), q.day()))) {
            return true;
        }
        Anniversary x = a.anniversary();
        Anniversary y = b.anniversary();
        return sameInstitution(x, y) && x.occasion().equals(y.occasion())
                && (different(x.ordinal(), y.ordinal()) || different(x.day(), y.day()));
    }

    private static Partnership partnership(String title, String lead, String original, OffsetDateTime time) {
        Matcher subject = TITLE_SUBJECT.matcher(title);
        Matcher collaboration = COLLABORATION.matcher(lead);
        if (!PARTNERSHIP_FOCUS.matcher(title).find() || !subject.find() || GENERAL_SUBJECTS.contains(subject.group(1))
                || !collaboration.find() || !Pattern.compile("(?<![a-z0-9가-힣])" + Pattern.quote(subject.group(1))
                + "(?:(?:은|는|이|가)(?=[,，\\s])|(?=[(,，\\s]))").matcher(bound(lead, 250)).find()) {
            return null;
        }
        int start = Math.max(0, collaboration.start() - 240);
        for (int index = start; index < collaboration.start(); index++) {
            if (lead.charAt(index) == '\n' || (lead.charAt(index) == '.'
                    && index + 1 < lead.length() && Character.isWhitespace(lead.charAt(index + 1)))) {
                start = index + 1;
            }
        }
        Set<String> partners = new HashSet<>();
        Matcher namedPartner = PARTNER.matcher(lead.substring(start, collaboration.start()));
        while (namedPartner.find()) {
            String partner = namedPartner.group(1);
            if (!GENERAL_SUBJECTS.contains(partner) && !partner.equals(subject.group(1))) {
                partners.add(partner);
            }
        }
        if (partners.isEmpty()) {
            return null;
        }
        Set<String> scope = new HashSet<>();
        Matcher technical = TECHNICAL.matcher(original);
        while (technical.find()) {
            if (!GENERIC_ANCHORS.contains(technical.group())) {
                scope.add(technical.group());
            }
        }
        return new Partnership(subject.group(1), partners, scope, day(lead, time));
    }

    private static Anniversary anniversary(String title, String lead, OffsetDateTime time) {
        Matcher occasion = ANNIVERSARY.matcher(lead);
        if (!occasion.find()) {
            return null;
        }
        // The organization must be attached to the first event's introductory context.
        String introduction = lead.substring(0, occasion.start());
        Set<String> councils = new HashSet<>();
        Matcher council = COUNCIL.matcher(introduction);
        while (council.find()) {
            councils.add(council.group(1));
        }
        // Guest and host institutions can both precede the occasion. Without a
        // unique institution we cannot safely assign either the event or a veto.
        if (councils.size() != 1 || !primaryTitle(title, lead, councils)) {
            return null;
        }
        return new Anniversary(councils, occasion.group(1), occasion.group(2), compact(occasion.group(3)),
                day(introduction, time));
    }

    private static boolean primaryTitle(String title, String lead, Set<String> councils) {
        if (councils.stream().anyMatch(name -> compact(title).contains(name.replace("위원회", "위")))) {
            return true;
        }
        // A quotation headline may omit the institution. Its distinctive words must
        // be supported by the opening itself, not by a later quoted background event.
        String opening = compact(bound(lead, 250));
        return TitleTokenizer.tokens(title).stream()
                .filter(word -> word.length() >= 2 && !word.matches("[0-9]+.*") && !GENERAL_TITLE_TERMS.contains(word))
                .filter(opening::contains).count() >= 3;
    }

    private static boolean sameSubject(Partnership a, Partnership b) {
        return a != null && b != null && a.subject().equals(b.subject());
    }

    private static boolean sameInstitution(Anniversary a, Anniversary b) {
        return a != null && b != null && !intersection(a.councils(), b.councils()).isEmpty();
    }

    private static boolean sameDay(LocalDate a, LocalDate b) {
        return a != null && a.equals(b);
    }

    private static LocalDate day(String text, OffsetDateTime time) {
        // A reporting date cannot stand in for an event explicitly located at a
        // relative time elsewhere in the same lead ("12일 ... 전날 체결한 협약").
        if (RELATIVE_EVENT_TIME.matcher(text).find()) {
            return null;
        }
        Matcher matcher = DAY.matcher(text);
        LocalDate resolved = null;
        while (matcher.find()) {
            if (time == null && (matcher.group(1) == null || matcher.group(2) == null)) {
                return null;
            }
            String preceding = text.substring(Math.max(0, matcher.start() - 12), matcher.start());
            if (preceding.matches(".*(?:지난달|다음달|지난해|작년).*")) {
                return null;
            }
            try {
                LocalDate result = LocalDate.of(matcher.group(1) == null ? time.getYear() : Integer.parseInt(matcher.group(1)),
                        matcher.group(2) == null ? time.getMonthValue() : Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                // Announcement and signing dates may coexist. Do not choose one of
                // several dates and turn that uncertainty into a positive or veto.
                if (different(resolved, result)
                        || (time != null && matcher.group(2) == null && result.isAfter(time.toLocalDate()))) {
                    return null;
                }
                resolved = result;
            } catch (DateTimeException ignored) {
                return null;
            }
        }
        return resolved;
    }

    private static <T> boolean different(T a, T b) {
        return a != null && b != null && !a.equals(b);
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static String foreground(String text) {
        Matcher background = BACKGROUND.matcher(text);
        return background.find() ? text.substring(0, background.start()) : text;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("인공지능\\s*\\(ai\\)", "ai").replace("인공지능", "ai");
    }

    private static String compact(String value) {
        return value.replaceAll("[^a-z0-9가-힣]", "");
    }

    private static String bound(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Partnership(String subject, Set<String> partners, Set<String> technicalScope, LocalDate day) {}
    private record Anniversary(Set<String> councils, String occasion, String ordinal, String action, LocalDate day) {}
    private record Profile(Partnership partnership, Anniversary anniversary) {}
}
