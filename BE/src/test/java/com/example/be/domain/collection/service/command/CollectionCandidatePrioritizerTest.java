package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionCandidatePrioritizerTest {

    @Test
    void selectsTopUrlsAcrossAllSourcesForOneTopic() {
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(2);
        CollectionCandidatePrioritizer prioritizer = new CollectionCandidatePrioritizer(
                properties, equalWeightScorer());
        Topic topic = Topic.builder()
                .id(7L)
                .optionalKeywords(List.of("HBM", "삼성", "양산"))
                .build();
        CollectionBatch first = batch(1L, topic, 11L,
                article("HBM 소식", "https://example.com/low", "요약"),
                article("삼성 HBM", "https://example.com/mid", "요약"));
        CollectionBatch second = batch(2L, topic, 12L,
                article("삼성 HBM 양산", "https://example.com/high", "요약"));

        Map<Long, List<CollectedArticle>> selected = prioritizer.prioritize(List.of(first, second));

        assertEquals(List.of("https://example.com/mid"), urls(selected.get(1L)));
        assertEquals(List.of("https://example.com/high"), urls(selected.get(2L)));
    }

    @Test
    void keepsEveryObservationOfASelectedNormalizedUrl() {
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(1);
        CollectionCandidatePrioritizer prioritizer = new CollectionCandidatePrioritizer(
                properties, equalWeightScorer());
        Topic topic = Topic.builder().id(7L).optionalKeywords(List.of("HBM")).build();
        CollectionBatch first = batch(1L, topic, 11L,
                article("HBM", "https://example.com/same?utm_source=a", "요약"));
        CollectionBatch second = batch(2L, topic, 12L,
                article("HBM", "https://example.com/same?fbclid=b", "요약"));

        Map<Long, List<CollectedArticle>> selected = prioritizer.prioritize(List.of(first, second));

        assertEquals(1, selected.get(1L).size());
        assertEquals(1, selected.get(2L).size());
    }

    @Test
    void ignoresDeprecatedSourceLimitAndUsesTopicLimit() {
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(3);
        CollectionCandidatePrioritizer prioritizer = new CollectionCandidatePrioritizer(
                properties, equalWeightScorer());
        Topic topic = Topic.builder()
                .id(7L)
                .optionalKeywords(List.of("HBM", "삼성", "양산"))
                .build();
        CollectionBatch batch = batch(1L, topic, 11L, 1,
                article("HBM", "https://example.com/low", "요약"),
                article("삼성 HBM 양산", "https://example.com/high", "요약"));

        Map<Long, List<CollectedArticle>> selected = prioritizer.prioritize(List.of(batch));

        assertEquals(
                List.of("https://example.com/high", "https://example.com/low"),
                urls(selected.get(1L)));
    }

    @Test
    void prioritizesOneRareMatchOverTwoCommonMatches() {
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(1);
        TopicFitScorer scorer = new TopicFitScorer((language, keywords) ->
                Map.of("반도체", 1.0d, "ai", 1.0d, "hbm4", 4.0d));
        CollectionCandidatePrioritizer prioritizer = new CollectionCandidatePrioritizer(properties, scorer);
        Topic topic = Topic.builder()
                .id(7L)
                .optionalKeywords(List.of("반도체", "AI", "HBM4"))
                .build();
        CollectionBatch batch = batch(1L, topic, 11L,
                article("반도체 AI 투자", "https://example.com/common", "요약"),
                article("HBM4 투자", "https://example.com/rare", "요약"));

        Map<Long, List<CollectedArticle>> selected = prioritizer.prioritize(List.of(batch));

        assertEquals(List.of("https://example.com/rare"), urls(selected.get(1L)));
    }

    private CollectionBatch batch(Long itemId,
                                  Topic topic,
                                  Long sourceId,
                                  CollectedArticle... articles) {
        Source source = Source.builder().id(sourceId).build();
        RobotsDecision robots = new RobotsDecision(
                true, Source.ROBOTS_STATUS_ALLOWED, LocalDateTime.now(),
                "https://example.com/robots.txt", null, null);
        return CollectionBatch.success(
                itemId,
                topic,
                source,
                CollectionOutcome.of(FetchResult.ok(List.of(articles)), robots));
    }

    private CollectionBatch batch(Long itemId,
                                  Topic topic,
                                  Long sourceId,
                                  int sourceLimit,
                                  CollectedArticle... articles) {
        Source source = Source.builder()
                .id(sourceId)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, sourceLimit, true))
                .build();
        RobotsDecision robots = new RobotsDecision(
                true, Source.ROBOTS_STATUS_ALLOWED, LocalDateTime.now(),
                "https://example.com/robots.txt", null, null);
        return CollectionBatch.success(
                itemId,
                topic,
                source,
                CollectionOutcome.of(FetchResult.ok(List.of(articles)), robots));
    }

    private CollectedArticle article(String title, String url, String summary) {
        return new CollectedArticle(title, url, summary, null, "example.com", "ko");
    }

    private List<String> urls(List<CollectedArticle> articles) {
        return articles.stream().map(CollectedArticle::canonicalUrl).toList();
    }

    private TopicFitScorer equalWeightScorer() {
        return new TopicFitScorer((language, keywords) -> Map.of());
    }
}
