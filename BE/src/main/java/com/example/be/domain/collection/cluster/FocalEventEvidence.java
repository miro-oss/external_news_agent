package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local event contexts and substantial attributed quotations, independent of background entities. */
final class FocalEventEvidence {
    private static final int CONTEXT_LIMIT = 2200;
    private static final int QUOTE_LIMIT = 16000;
    private static final Pattern EVENT = Pattern.compile(
            "대표연설|기조연설|인터뷰|기자회견|설명회|신고식|협약식|간담회");
    private static final Pattern RATE = Pattern.compile("[0-9]+(?:\\.[0-9]+)?\\s*%");
    private static final Pattern RALLY = Pattern.compile("상승|급등|강세");
    private static final Pattern BACKGROUND_CONTEXT = Pattern.compile(
            "(?:앞서|한편|과거|일례로|배경으로)[^.!?\n]*$");
    private static final Pattern DAY = Pattern.compile("(?<![0-9])([0-3]?[0-9])일");
    private static final Pattern QUOTE = Pattern.compile("\"([^\"\n]{4,800})\"|“([^”\n]{4,800})”");
    private static final Pattern NON_TEXT = Pattern.compile("[^a-z0-9가-힣]");
    private static final Pattern WORD = Pattern.compile("[a-z0-9가-힣]+");
    private static final Set<String> GENERAL = Set.of(
            "반도체", "산업", "기술", "기업", "시장", "투자", "제조", "공개", "발표", "개최",
            "계획", "추진", "확대", "강화", "검토", "전망", "관련", "업계", "분석", "공식",
            "미국", "중국", "한국", "일본", "국내", "해외", "글로벌", "올해", "내년", "서울",
            "국회", "기자", "대표", "대표연설", "기조연설", "인터뷰", "기자회견", "설명회",
            "신고식", "협약식", "간담회", "오전", "오후", "이날", "통해", "따르면");

    private final Map<Long, Profile> profiles = new HashMap<>();

    FocalEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String body = normalize(ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()));
            String summary = bound(normalize(article.summary()), 700);
            String lead = bound(body, CONTEXT_LIMIT);
            List<Context> contexts = new ArrayList<>(contexts(lead));
            contexts.addAll(contexts(bound(summary, 700)));
            List<String> sourceQuotes = new ArrayList<>(quotes(bound(body, CONTEXT_LIMIT)));
            // Search/RSS summaries can concatenate secondary headlines. Only the first
            // quotation may identify the summary's primary report.
            sourceQuotes.addAll(quotes(bound(summary, 700)).stream().limit(1).toList());
            if (title.startsWith("[전문]") || bound(body, 1000).contains("연설 전문")) {
                String transcript = bound(body, QUOTE_LIMIT);
                for (int offset = 0; offset < transcript.length(); offset += 400) {
                    sourceQuotes.add(bound(transcript.substring(offset), 800));
                }
            }
            profiles.put(article.articleId(), new Profile(compact(title), specific(title),
                    bound(body, 350) + " " + summary, contexts, quotes(title).stream().map(quote -> new TitleQuote(words(quote), compact(quote).length())).toList(),
                    sourceQuotes.stream().map(FocalEventEvidence::compact).toList(),
                    rates(bound(body, 700) + " " + summary),
                    new DeterministicEntityExtractor().extractOrganizations(bound(body, 350), null),
                    RALLY.matcher(bound(body, 350)).find()));
        }
    }

    boolean matches(long left, long right) {
        Profile a = profiles.get(left);
        Profile b = profiles.get(right);
        if (quotedTitleInSource(a, b) || quotedTitleInSource(b, a)) {
            return true;
        }
        if (sameRallySubjects(a, b)) {
            return true;
        }
        if (!subjectInLead(a, b) || !subjectInLead(b, a)) {
            return false;
        }
        Set<String> commonSubjects = new HashSet<>(a.subjects());
        commonSubjects.retainAll(b.subjects());
        if (commonSubjects.size() >= 2 && commonSubjects.stream().anyMatch(word -> word.length() >= 3)
                && commonSubjects.stream().anyMatch(word -> RALLY.matcher(word).matches())
                && !java.util.Collections.disjoint(a.rates(), b.rates())) {
            return true;
        }
        for (Context first : a.contexts()) {
            for (Context second : b.contexts()) {
                if (!first.kind().equals(second.kind())) {
                    continue;
                }
                if (!first.days().isEmpty() && !second.days().isEmpty()
                        && java.util.Collections.disjoint(first.days(), second.days())) {
                    continue;
                }
                Set<String> shared = new HashSet<>(first.words());
                shared.retainAll(second.words());
                if (shared.size() >= 3 && shared.stream().anyMatch(word -> word.length() >= 4)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameRallySubjects(Profile a, Profile b) {
        Set<String> commonOrganizations = new HashSet<>(a.organizations());
        commonOrganizations.retainAll(b.organizations());
        if (!a.rally() || !b.rally() || commonOrganizations.size() < 2) {
            return false;
        }
        String left = a.title();
        String right = b.title();
        return a.subjects().stream().anyMatch(word -> word.length() >= 4 && right.contains(word))
                || b.subjects().stream().anyMatch(word -> word.length() >= 4 && left.contains(word));
    }

    private static Set<String> rates(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = RATE.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group().replaceAll("\\s", ""));
        }
        return result;
    }

    private static boolean subjectInLead(Profile title, Profile source) {
        return title.subjects().stream().anyMatch(word -> source.lead().contains(word));
    }

    private static boolean quotedTitleInSource(Profile title, Profile source) {
        for (TitleQuote quote : title.titleQuotes()) {
            List<String> words = quote.words();
            if (words.size() < 4 || quote.length() < 16) {
                continue;
            }
            for (String candidate : source.sourceQuotes()) {
                int position = 0;
                int matched = 0;
                for (String word : words) {
                    int found = candidate.indexOf(word, position);
                    if (found >= 0) {
                        position = found + word.length();
                        matched++;
                    }
                }
                if (matched >= 4 && matched >= Math.ceil(words.size() * 0.8)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Context> contexts(String text) {
        List<Context> result = new ArrayList<>();
        Matcher events = EVENT.matcher(text);
        while (events.find()) {
            String preceding = text.substring(Math.max(0, events.start() - 240), events.start());
            if (BACKGROUND_CONTEXT.matcher(preceding).find()) {
                continue;
            }
            String context = text.substring(Math.max(0, events.start() - 240),
                    Math.min(text.length(), events.end() + 160));
            Set<String> days = new HashSet<>();
            Matcher dates = DAY.matcher(context);
            while (dates.find()) {
                days.add(dates.group(1));
            }
            result.add(new Context(events.group(), specific(context), days));
        }
        return result;
    }

    private static Set<String> specific(String text) {
        Set<String> words = new HashSet<>(TitleTokenizer.tokens(text));
        words.removeAll(GENERAL);
        words.removeIf(word -> word.matches("[0-9]+.*"));
        return words;
    }

    private static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            result.addAll(TitleTokenizer.tokens(matcher.group()));
        }
        return result;
    }

    private static List<String> quotes(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = QUOTE.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
        return result;
    }

    private static String compact(String text) {
        return NON_TEXT.matcher(text).replaceAll("");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String bound(String value, int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private record TitleQuote(List<String> words, int length) {}
    private record Context(String kind, Set<String> words, Set<String> days) {}
    private record Profile(String title, Set<String> subjects, String lead, List<Context> contexts,
                           List<TitleQuote> titleQuotes, List<String> sourceQuotes, Set<String> rates,
                           Set<String> organizations, boolean rally) {}
}
