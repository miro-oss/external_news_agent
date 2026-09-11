package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Negative evidence about the primary event, independent of shared background companies. */
final class EventScopeEvidence {
    private static final int LEAD_LIMIT = 350;
    private static final int CONTEXT_LIMIT = 2200;
    private static final Pattern TITLE_BREAK = Pattern.compile("…|\\.{2,}|[;；]");
    private static final Pattern PARAGRAPH = Pattern.compile("\\n+");
    private static final Pattern RECOMPOSITION = Pattern.compile(
            "리밸런싱|리밸런스|지수\\s*정기\\s*변경|정기\\s*변경|비중\\s*(?:조절|조정|재조정)"
                    + "|구성\\s*종목\\s*(?:변경|편입|편출)|종목별?\\s*비중\\s*상한");
    private static final Pattern MARKET_SUBJECT = Pattern.compile(
            "코스피|코스닥|증시|주가|지수|[일이삼사오육칠팔구십백천만]+피");
    private static final Pattern MOVEMENT = Pattern.compile(
            "상승|급등|강세|오름세|오른|올랐|하락|급락|약세|내림세|내린|떨어"
                    + "|순매수|순매도|매수세|출발|마감|탈환|돌파|회복");
    private static final Pattern CONCRETE_ACTION = Pattern.compile(
            "파트너십|협약|제휴|동맹|공동\\s*(?:개발|구축)|합작|협력\\s*계약|계약\\s*체결"
                    + "|지분\\s*투자|투자\\s*유치|시리즈\\s*[a-z]|인수|합병"
                    + "|수주|낙찰|공급\\s*계약|사업자.{0,12}선정"
                    + "|감사.{0,8}(?:착수|돌입)|조사.{0,8}(?:착수|돌입)"
                    + "|공장\\s*(?:착공|준공)|신공장|신제품|출시");
    private static final Pattern DIGEST_TITLE = Pattern.compile(
            "(?:증시|종목|기업).{0,18}(?:키워드|브리핑|모아보기|한눈에|이슈|소식\\s*정리)");
    private static final Pattern DIGEST_LEAD = Pattern.compile(
            "(?:개별|종목별)\\s*(?:이슈|소식|재료)|검색\\s*상위"
                    + "|(?:주요|여러)\\s*(?:종목|기업).{0,18}(?:이슈|소식)");
    private static final Pattern SENTENCE_SUBJECT = Pattern.compile(
            "(?:^|[.!?]\\s+)(?:한편\\s*[,，]?\\s*|또한\\s*[,，]?\\s*)?"
                    + "([a-z0-9가-힣&·-]{2,30}?)(?:은|는|이|가|\\s+역시)\\s+");
    private static final Set<String> GENERAL_SUBJECTS = Set.of(
            "이날", "오늘", "전날", "양사", "회사", "업계", "시장", "증시", "주가", "관계자",
            "기업", "기관", "외국인", "개인", "투자자", "지수", "이번", "올해", "그들");

    private final Map<Long, Scope> profiles = new HashMap<>();

    EventScopeEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()));
            String context = bound(body.isBlank() ? normalize(article.summary()) : body, CONTEXT_LIMIT);
            profiles.put(article.articleId(), scope(title, context));
        }
    }

    /** Apply across every member of two components before adding a new inferred edge. */
    boolean conflicts(long left, long right) {
        Scope first = profiles.getOrDefault(left, Scope.UNKNOWN);
        Scope second = profiles.getOrDefault(right, Scope.UNKNOWN);
        return (first == Scope.INDEX_RECOMPOSITION && second == Scope.MARKET_OBSERVATION)
                || (second == Scope.INDEX_RECOMPOSITION && first == Scope.MARKET_OBSERVATION)
                || (first == Scope.MARKET_DIGEST && specific(second))
                || (second == Scope.MARKET_DIGEST && specific(first));
    }

    private static Scope scope(String title, String context) {
        String lead = bound(context, LEAD_LIMIT);
        boolean digestTitle = DIGEST_TITLE.matcher(title).find();
        boolean distributedLead = DIGEST_LEAD.matcher(lead).find();
        boolean generalDigestTitle = digestTitle && !CONCRETE_ACTION.matcher(title).find()
                && !RECOMPOSITION.matcher(title).find();
        if ((digestTitle || distributedLead)
                && independentReports(context, title, distributedLead || generalDigestTitle)) {
            return Scope.MARKET_DIGEST;
        }

        String openingTitle = TITLE_BREAK.split(title, 2)[0];
        // The headline's focal action takes precedence over later market background.
        if (RECOMPOSITION.matcher(openingTitle).find()) {
            return Scope.INDEX_RECOMPOSITION;
        }
        if (CONCRETE_ACTION.matcher(openingTitle).find()) {
            return Scope.SINGLE_EVENT;
        }
        if (MOVEMENT.matcher(openingTitle).find()
                && MARKET_SUBJECT.matcher(openingTitle + " " + lead).find()) {
            return Scope.MARKET_OBSERVATION;
        }
        // A clear market headline must not inherit a secondary rebalance explanation.
        String openingLead = PARAGRAPH.split(lead, 2)[0];
        if (RECOMPOSITION.matcher(openingLead).find()) {
            return Scope.INDEX_RECOMPOSITION;
        }
        if (MARKET_SUBJECT.matcher(openingLead).find() && MOVEMENT.matcher(openingLead).find()) {
            return Scope.MARKET_OBSERVATION;
        }
        if (CONCRETE_ACTION.matcher(openingLead).find()) {
            return Scope.SINGLE_EVENT;
        }
        return Scope.UNKNOWN;
    }

    /** Several companies in one agreement are not several independent reports. */
    private static boolean independentReports(String context, String title, boolean distributedFocus) {
        List<Report> reports = new ArrayList<>();
        for (String paragraph : PARAGRAPH.split(context)) {
            if (!CONCRETE_ACTION.matcher(paragraph).find() && !RECOMPOSITION.matcher(paragraph).find()) {
                continue;
            }
            Matcher subjects = SENTENCE_SUBJECT.matcher(paragraph);
            while (subjects.find()) {
                String subject = subjects.group(1);
                if (!GENERAL_SUBJECTS.contains(subject)) {
                    reports.add(new Report(subject, paragraph));
                }
            }
        }
        for (int left = 0; left < reports.size(); left++) {
            for (int right = left + 1; right < reports.size(); right++) {
                Report first = reports.get(left);
                Report second = reports.get(right);
                if (!first.subject().equals(second.subject())
                        && !first.paragraph().contains(second.subject())
                        && !second.paragraph().contains(first.subject())
                        && (distributedFocus || (title.contains(first.subject())
                        && title.contains(second.subject())))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean specific(Scope scope) {
        return scope == Scope.SINGLE_EVENT || scope == Scope.INDEX_RECOMPOSITION;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static String bound(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private record Report(String subject, String paragraph) {}

    private enum Scope { UNKNOWN, MARKET_OBSERVATION, INDEX_RECOMPOSITION, SINGLE_EVENT, MARKET_DIGEST }
}
