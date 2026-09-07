package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Topic-local lexical evidence; title and bounded lead are separate from background entities. */
final class EventTextEvidence {
    private static final int LEAD_LIMIT = 700;
    private static final Pattern NON_TEXT = Pattern.compile("[^a-z0-9가-힣]");
    private static final Pattern NUMBER_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern EDITORIAL_MARKERS = Pattern.compile(
            "관련\\s*상세\\s*보도|후속\\s*확인|업계\\s*분석");
    private static final Set<String> GENERAL_TERMS = Set.of(
            "반도체", "산업", "기술", "기업", "시장", "공장", "장비", "사업", "투자",
            "제조", "공개", "발표", "개최", "계획", "추진", "확대", "강화", "검토", "전망",
            "기대", "지원", "후속", "확인", "관련", "상세", "보도", "업계", "분석", "공식",
            "미국", "중국", "한국", "일본", "국내", "해외", "글로벌", "올해", "내년");

    private final Map<Long, Profile> profiles = new HashMap<>();
    private final Map<String, Double> weights = new HashMap<>();

    EventTextEvidence(List<ClusterArticle> voting, BreakingNewsDetector detector) {
        Map<String, Integer> frequency = new HashMap<>();
        for (ClusterArticle article : voting) {
            String title = detector.coreTitle(article.title());
            String body = ArticleBodyCleaner.withoutTrailingBoilerplate(article.body());
            String lead = body.length() >= 400 ? body : article.summary();
            if (lead == null) {
                lead = "";
            }
            lead = lead.substring(0, Math.min(LEAD_LIMIT, lead.length()));
            Profile profile = new Profile(grams(title), grams(lead),
                    TitleTokenizer.tokens(title), compact(lead), 0, 0);
            profiles.put(article.articleId(), profile);
            Set<String> document = new TreeSet<>(profile.title());
            document.addAll(profile.lead());
            document.forEach(gram -> frequency.merge(gram, 1, Integer::sum));
        }
        frequency.forEach((gram, count) -> {
            double idf = Math.log1p((double) voting.size() / (1 + count));
            weights.put(gram, idf * idf);
        });
        profiles.replaceAll((id, profile) -> new Profile(profile.title(), profile.lead(),
                profile.terms(), profile.leadText(), norm(profile.title()), norm(profile.lead())));
    }

    Evidence compare(long left, long right, Set<String> titleOrganizations) {
        Profile first = profiles.get(left);
        Profile second = profiles.get(right);
        double title = cosine(first.title(), second.title(), first.titleNorm(), second.titleNorm());
        double lead = cosine(first.lead(), second.lead(), first.leadNorm(), second.leadNorm());
        Set<String> firstTerms = specificTerms(first.terms(), titleOrganizations);
        Set<String> secondTerms = specificTerms(second.terms(), titleOrganizations);
        Set<String> terms = new TreeSet<>(firstTerms);
        terms.retainAll(secondTerms);
        // A shared organization alone is not a second event clue for the weak-title edge.
        boolean organizationSupported = !terms.isEmpty();
        // Short, dissimilar titles need reciprocal subject clues in the leads, not just
        // similar boilerplate. No body-only edge and no unbounded background-text comparison.
        boolean reciprocalLeadClues = leadClues(firstTerms, second.leadText()) >= 2
                && leadClues(secondTerms, first.leadText()) >= 2;
        boolean eventMatch = (title >= 0.45)
                || (title >= 0.30 && !terms.isEmpty())
                || (title >= 0.20 && terms.size() >= 2)
                || (title >= 0.15 && terms.size() >= 3)
                || (title >= 0.05 && lead >= 0.20 && reciprocalLeadClues);
        return new Evidence(title, lead, eventMatch, organizationSupported);
    }

    private Set<String> specificTerms(Set<String> original, Set<String> organizations) {
        Set<String> terms = new TreeSet<>(original);
        terms.removeAll(GENERAL_TERMS);
        organizations.forEach(organization -> terms.removeAll(TitleTokenizer.tokens(organization)));
        terms.removeIf(term -> term.matches("[0-9]+"));
        return terms;
    }

    private long leadClues(Set<String> terms, String otherLead) {
        return terms.stream().filter(term -> term.length() >= 3 && otherLead.contains(term)).count();
    }

    private double norm(Set<String> grams) {
        return Math.sqrt(grams.stream().mapToDouble(weights::get).sum());
    }

    private double cosine(Set<String> left, Set<String> right, double leftNorm, double rightNorm) {
        if (left.size() < 4 || right.size() < 4) {
            return 0;
        }
        double dot = 0;
        for (String gram : left) {
            double weight = weights.get(gram);
            if (right.contains(gram)) {
                dot += weight;
            }
        }
        return Math.min(1, dot / (leftNorm * rightNorm));
    }

    private static Set<String> grams(String text) {
        String normalized = compact(text);
        Set<String> result = new TreeSet<>();
        for (int index = 0; index + 3 <= normalized.length(); index++) {
            result.add(normalized.substring(index, index + 3));
        }
        return result;
    }

    private static String compact(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = EDITORIAL_MARKERS.matcher(normalized).replaceAll("");
        normalized = NUMBER_COMMA.matcher(normalized).replaceAll("");
        return NON_TEXT.matcher(normalized).replaceAll("");
    }

    record Evidence(double titleSimilarity, double leadSimilarity,
                    boolean eventMatch, boolean organizationSupported) {}

    private record Profile(Set<String> title, Set<String> lead, Set<String> terms,
                           String leadText, double titleNorm, double leadNorm) {}
}
