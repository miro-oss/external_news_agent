package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.topics.entity.Topic;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

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
        String haystack = haystackOf(article);

        return containsEvery(haystack, topic.getRequiredKeywords())
                && containsAny(haystack, topic.getOptionalKeywords())
                && containsNone(haystack, topic.getExcludedKeywords());
    }

    public static List<CollectedArticle> filter(Topic topic, List<CollectedArticle> articles) {
        return articles.stream()
                .filter(article -> matches(topic, article))
                .toList();
    }

    private static String haystackOf(CollectedArticle article) {
        return (nullToEmpty(article.title()) + " " + nullToEmpty(article.summary()))
                .toLowerCase(Locale.ROOT);
    }

    /** AND. 하나라도 없으면 탈락한다. */
    private static boolean containsEvery(String haystack, List<String> keywords) {
        return isEmpty(keywords) || keywords.stream().allMatch(keyword -> contains(haystack, keyword));
    }

    /**
     * OR. <b>비어 있으면 조건 자체가 없는 것</b>이라 통과시킨다. 하나라도 적혀 있으면 그중 하나는 맞아야 한다.
     */
    private static boolean containsAny(String haystack, List<String> keywords) {
        return isEmpty(keywords) || keywords.stream().anyMatch(keyword -> contains(haystack, keyword));
    }

    /** NOT. 하나라도 있으면 탈락한다. */
    private static boolean containsNone(String haystack, List<String> keywords) {
        return isEmpty(keywords) || keywords.stream().noneMatch(keyword -> contains(haystack, keyword));
    }

    private static boolean contains(String haystack, String keyword) {
        return StringUtils.hasText(keyword) && haystack.contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isEmpty(List<String> keywords) {
        return keywords == null || keywords.isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
