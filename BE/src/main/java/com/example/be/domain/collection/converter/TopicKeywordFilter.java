package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.topics.entity.Topic;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 주제의 키워드로 수집 결과를 거른다.
 *
 * <p><b>FEED 소스에는 질의어가 없다.</b> 한국경제 경제 피드는 주제와 무관하게 그날 기사를 전부 준다.
 * 여기서 거르지 않으면 "HBM" 주제에 경제 뉴스 전체가 들어와 주제 × 소스 조합이 의미를 잃는다.
 *
 * <p>제목과 요약만 본다. 본문은 이 단계에 없다(F6에서 받는다).
 */
public final class TopicKeywordFilter {

    private TopicKeywordFilter() {
    }

    public static boolean matches(Topic topic, CollectedArticle article) {
        return evaluate(topic, article).matches();
    }

    public static List<CollectedArticle> filter(Topic topic, List<CollectedArticle> articles) {
        return articles.stream()
                .filter(article -> matches(topic, article))
                .toList();
    }

    /**
     * AND/NOT은 기존처럼 통과 여부만 결정하고, OR은 일치한 선택 키워드의 가중 비율로 점수화한다.
     * 선택 키워드가 없으면 조건도 없으므로 1.0이다.
     */
    public static MatchResult evaluate(Topic topic, CollectedArticle article) {
        return evaluate(topic, article, Map.of());
    }

    public static MatchResult evaluate(Topic topic,
                                       CollectedArticle article,
                                       Map<String, Double> weights) {
        String haystack = haystackOf(article);
        List<String> optionalKeywords = normalizedOptionalKeywords(topic);
        int matchedOptionalCount = (int) optionalKeywords.stream()
                .filter(keyword -> haystack.contains(keyword))
                .count();
        double topicFit = weightedFit(haystack, optionalKeywords, weights);
        boolean matches = containsEvery(haystack, topic.getRequiredKeywords())
                && (optionalKeywords.isEmpty() || matchedOptionalCount > 0)
                && containsNone(haystack, topic.getExcludedKeywords());
        return new MatchResult(matches, topicFit, matchedOptionalCount, optionalKeywords.size());
    }

    public static double topicFit(Topic topic,
                                  String title,
                                  String summary,
                                  Map<String, Double> weights) {
        String haystack = haystackOf(title, summary);
        List<String> optionalKeywords = normalizedOptionalKeywords(topic);
        return weightedFit(haystack, optionalKeywords, weights);
    }

    private static double weightedFit(String haystack,
                                      List<String> optionalKeywords,
                                      Map<String, Double> weights) {
        if (optionalKeywords.isEmpty()) {
            return 1.0;
        }
        double totalWeight = optionalKeywords.stream()
                .mapToDouble(keyword -> validWeight(weights.get(keyword)))
                .sum();
        double matchedWeight = optionalKeywords.stream()
                .filter(haystack::contains)
                .mapToDouble(keyword -> validWeight(weights.get(keyword)))
                .sum();
        return matchedWeight / totalWeight;
    }

    private static String haystackOf(CollectedArticle article) {
        return haystackOf(article.title(), article.summary());
    }

    private static String haystackOf(String title, String summary) {
        return (nullToEmpty(title) + " " + nullToEmpty(summary))
                .toLowerCase(Locale.ROOT);
    }

    /** AND. 하나라도 없으면 탈락한다. */
    private static boolean containsEvery(String haystack, List<String> keywords) {
        return isEmpty(keywords) || keywords.stream().allMatch(keyword -> contains(haystack, keyword));
    }

    /** NOT. 하나라도 있으면 탈락한다. */
    private static boolean containsNone(String haystack, List<String> keywords) {
        return isEmpty(keywords) || keywords.stream().noneMatch(keyword -> contains(haystack, keyword));
    }

    private static boolean contains(String haystack, String keyword) {
        return StringUtils.hasText(keyword) && haystack.contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    public static List<String> normalizedOptionalKeywords(Topic topic) {
        return normalized(topic == null ? null : topic.getOptionalKeywords());
    }

    private static List<String> normalized(List<String> keywords) {
        if (isEmpty(keywords)) {
            return List.of();
        }
        return keywords.stream()
                .filter(StringUtils::hasText)
                .map(keyword -> keyword.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static boolean isEmpty(List<String> keywords) {
        return keywords == null || keywords.isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double validWeight(Double weight) {
        return weight != null && Double.isFinite(weight) && weight > 0.0d ? weight : 1.0d;
    }

    public record MatchResult(
            boolean matches,
            double topicFit,
            int matchedOptionalCount,
            int optionalKeywordCount
    ) {
    }
}
