package com.example.be.domain.collection.cluster;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A launch's named version can veto shared company names and references to earlier products. */
final class VersionedProductEventEvidence {
    private static final int LEAD_LIMIT = 1200;
    private static final Pattern NAME = Pattern.compile(
            "(?<![a-z0-9가-힣])([a-z가-힣][a-z가-힣-]{0,30}?)[\\s-]*v?"
                    + "([0-9]+(?:\\.[0-9]+)*)(?![a-z0-9]|\\.[0-9])");
    private static final Pattern TITLE_ACTION = Pattern.compile(
            "공개|발표|출시|선보|내놓|launch|releas|unveil|introduc|announc");
    private static final Pattern SPECULATION = Pattern.compile("전망|예상|가능성|검토|소문|루머|rumou?r|might|may\\s");
    private static final Pattern PRODUCT_CONTEXT = Pattern.compile(
            "모델|제품|버전|신형|신제품|시리즈|소프트웨어|스마트폰|프로세서|칩|model|product|version|software");
    private static final Pattern COMPLETED_REVEAL = Pattern.compile(
            "공개했|발표했|출시했|선보였|내놓았|출시하며|출시해\\s|공개하며"
                    + "|\\b(?:launched|released|unveiled|introduced|announced)\\b");
    private static final Pattern BACKGROUND = Pattern.compile(
            "^\\s*(?:앞서|한편|과거|이전|지난|전작|기존|previously|earlier|last\\s)");

    private final Map<Long, Map<String, Set<String>>> profiles = new HashMap<>();

    VersionedProductEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        for (ClusterArticle article : articles) {
            String title = normalize(detector.coreTitle(article.title()));
            String primary = normalize(ArticleEvidenceText.foreground(ArticleEvidenceText.primary(article), LEAD_LIMIT));
            profiles.put(article.articleId(), focalVersions(title, primary));
        }
    }

    boolean conflicts(long left, long right) {
        Map<String, Set<String>> a = profiles.getOrDefault(left, Map.of());
        Map<String, Set<String>> b = profiles.getOrDefault(right, Map.of());
        for (var entry : a.entrySet()) {
            Set<String> other = b.get(entry.getKey());
            // A release covering multiple versions is not proof that their individual releases conflict.
            if (other != null && java.util.Collections.disjoint(entry.getValue(), other)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Set<String>> focalVersions(String title, String primary) {
        if (!TITLE_ACTION.matcher(title).find() || SPECULATION.matcher(title).find()
                || !PRODUCT_CONTEXT.matcher(primary).find()) {
            return Map.of();
        }
        List<NamedVersion> titleNames = names(title);
        if (titleNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> versions = new HashMap<>();
        for (String sentence : primary.split("(?<=[.!?])\\s+|\\n+")) {
            if (BACKGROUND.matcher(sentence).find()) {
                continue;
            }
            List<NamedVersion> sentenceNames = names(sentence);
            for (NamedVersion name : sentenceNames) {
                if (titleNames.stream().noneMatch(name::sameProduct)) {
                    continue;
                }
                Matcher reveal = COMPLETED_REVEAL.matcher(sentence);
                while (reveal.find()) {
                    // Do not borrow a later product's launch predicate or an earlier launch's comparison.
                    int from = Math.min(name.end(), reveal.end());
                    int to = Math.max(name.start(), reveal.start());
                    if (to - from > 180 || sentenceNames.stream().anyMatch(other -> other.start() >= from
                            && other.end() <= to && !name.sameProduct(other))) {
                        continue;
                    }
                    versions.computeIfAbsent(name.family(), ignored -> new HashSet<>()).add(name.version());
                    break;
                }
            }
        }
        return versions;
    }

    private static List<NamedVersion> names(String text) {
        java.util.ArrayList<NamedVersion> result = new java.util.ArrayList<>();
        Matcher matcher = NAME.matcher(text);
        while (matcher.find()) {
            String family = matcher.group(1).replace("-", "");
            if (family.length() >= 2) {
                result.add(new NamedVersion(family, matcher.group(2), matcher.start(), matcher.end()));
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record NamedVersion(String family, String version, int start, int end) {
        boolean sameProduct(NamedVersion other) {
            return family.equals(other.family()) && version.equals(other.version());
        }
    }
}
