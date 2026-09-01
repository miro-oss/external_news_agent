package com.example.be.domain.collection.scoring;

import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicFitScorerTest {

    @Test
    void reversesUniformRankingWhenOneRareKeywordCarriesMoreEvidence() {
        Topic topic = topic(List.of("반도체", "AI", "HBM4"));
        TopicFitScorer scorer = new TopicFitScorer((language, keywords) ->
                Map.of("반도체", 1.0d, "ai", 1.0d, "hbm4", 4.0d));

        double commonTwo = scorer.score(topic, "반도체 AI 투자", null, "ko", null);
        double rareOne = scorer.score(topic, "HBM4 투자", null, "ko", null);

        assertTrue(rareOne > commonTwo);
        assertEquals(2.0d / 6.0d, commonTwo, 0.000001);
        assertEquals(4.0d / 6.0d, rareOne, 0.000001);
    }

    @Test
    void normalizesTwentyKeywordAndThreeKeywordTopicsToTheSameScale() {
        List<String> twentyKeywords = IntStream.rangeClosed(1, 20)
                .mapToObj(number -> "k" + number)
                .toList();
        Map<String, Double> weights = new LinkedHashMap<>();
        twentyKeywords.forEach(keyword -> weights.put(keyword, 1.0d));
        weights.put("common-a", 1.0d);
        weights.put("common-b", 1.0d);
        weights.put("rare", 2.0d);
        TopicFitScorer scorer = new TopicFitScorer((language, keywords) -> weights);

        String tenMatches = String.join(" ", twentyKeywords.subList(0, 10));
        double largeTopicFit = scorer.score(topic(twentyKeywords), tenMatches, null, "ko", null);
        double smallTopicFit = scorer.score(
                topic(List.of("common-a", "common-b", "rare")), "rare", null, "ko", null);

        assertEquals(0.5d, largeTopicFit, 0.000001);
        assertEquals(0.5d, smallTopicFit, 0.000001);
    }

    @Test
    void returnsFullFitWhenOptionalKeywordsAreEmpty() {
        TopicFitScorer scorer = new TopicFitScorer((language, keywords) -> {
            throw new AssertionError("빈 선택 키워드는 IDF를 조회하지 않아야 한다.");
        });

        assertEquals(1.0d, scorer.score(topic(List.of()), "기사", "요약", null, null));
    }

    private Topic topic(List<String> optionalKeywords) {
        return Topic.builder().optionalKeywords(optionalKeywords).build();
    }
}
