package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicKeywordFilterTest {

    @Test
    void requiresEveryRequiredKeyword() {
        Topic topic = topic(List.of("HBM", "삼성"), List.of(), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("삼성전자 HBM4 양산", "요약")));
        assertFalse(TopicKeywordFilter.matches(topic, article("SK하이닉스 HBM4 양산", "요약")));
    }

    @Test
    void requiresAtLeastOneOptionalKeyword() {
        Topic topic = topic(List.of(), List.of("HBM", "DRAM"), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("DRAM 가격 반등", "요약")));
        assertFalse(TopicKeywordFilter.matches(topic, article("파운드리 수주", "요약")));
    }

    @Test
    void rejectsExcludedKeyword() {
        Topic topic = topic(List.of("HBM"), List.of(), List.of("루머"));

        assertTrue(TopicKeywordFilter.matches(topic, article("HBM4 양산 확정", "요약")));
        assertFalse(TopicKeywordFilter.matches(topic, article("HBM4 양산 루머", "요약")));
    }

    /**
     * 조건이 비어 있으면 조건이 없는 것이다. 빈 optional 목록 때문에 전부 탈락하면 안 된다.
     */
    @Test
    void passesEverythingWhenNoKeywordIsSet() {
        Topic topic = topic(List.of(), List.of(), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("아무 기사", "요약")));
    }

    @Test
    void treatsBlankOptionalKeywordsAsNoOptionalCondition() {
        Topic topic = topic(List.of(), List.of("", "   "), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("아무 기사", "요약")));
        assertEquals(1.0, TopicKeywordFilter.evaluate(topic, article("아무 기사", "요약")).topicFit());
    }

    @Test
    void looksAtSummaryAsWell() {
        Topic topic = topic(List.of("HBM"), List.of(), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("반도체 소식", "HBM4 양산이 시작됐다")));
    }

    @Test
    void ignoresCase() {
        Topic topic = topic(List.of("hbm"), List.of(), List.of());

        assertTrue(TopicKeywordFilter.matches(topic, article("HBM4 shipments", "summary")));
    }

    @Test
    void handlesArticlesWithoutText() {
        Topic topic = topic(List.of("HBM"), List.of(), List.of());

        assertFalse(TopicKeywordFilter.matches(topic, article(null, null)));
    }

    @Test
    void filtersList() {
        Topic topic = topic(List.of("HBM"), List.of(), List.of());
        List<CollectedArticle> articles = List.of(
                article("HBM4 양산", "요약"),
                article("파운드리 수주", "요약"),
                article("HBM3E 공급", "요약"));

        assertEquals(2, TopicKeywordFilter.filter(topic, articles).size());
    }

    @Test
    void calculatesUniformTopicFitFromOptionalKeywords() {
        Topic topic = topic(List.of("반도체"), List.of("HBM", "삼성", "양산"), List.of());

        TopicKeywordFilter.MatchResult result = TopicKeywordFilter.evaluate(
                topic, article("삼성 반도체 투자", "HBM 공급 확대"));

        assertTrue(result.matches());
        assertEquals(2, result.matchedOptionalCount());
        assertEquals(3, result.optionalKeywordCount());
        assertEquals(2.0 / 3.0, result.topicFit(), 0.000001);
    }

    @Test
    void givesRareMatchedKeywordMoreWeight() {
        Topic topic = topic(List.of(), List.of("반도체", "AI", "HBM4"), List.of());

        double commonFit = TopicKeywordFilter.evaluate(
                topic,
                article("반도체 AI 투자", "요약"),
                Map.of("반도체", 1.0, "ai", 1.0, "hbm4", 4.0)).topicFit();
        double rareFit = TopicKeywordFilter.evaluate(
                topic,
                article("HBM4 투자", "요약"),
                Map.of("반도체", 1.0, "ai", 1.0, "hbm4", 4.0)).topicFit();

        assertEquals(2.0 / 6.0, commonFit, 0.000001);
        assertEquals(4.0 / 6.0, rareFit, 0.000001);
    }

    @Test
    void usesFullFitWhenOptionalKeywordsAreEmpty() {
        Topic topic = topic(List.of("HBM"), List.of(), List.of());

        assertEquals(1.0, TopicKeywordFilter.evaluate(
                topic, article("HBM 양산", "요약")).topicFit());
    }

    private Topic topic(List<String> required, List<String> optional, List<String> excluded) {
        return Topic.builder()
                .name("키워드 테스트")
                .queryText("HBM")
                .requiredKeywords(required)
                .optionalKeywords(optional)
                .excludedKeywords(excluded)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }

    private CollectedArticle article(String title, String summary) {
        return new CollectedArticle(title, "https://example.com/1", summary, null, "example.com", "ko");
    }
}
