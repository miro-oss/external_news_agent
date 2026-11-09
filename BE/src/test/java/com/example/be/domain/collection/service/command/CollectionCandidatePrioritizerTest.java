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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionCandidatePrioritizerTest {

    @Test
    void technologyCapRetainsARelevantSmallSourceAlongsideProlificNewsSource() {
        var properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(2);
        var prioritizer = new CollectionCandidatePrioritizer(properties, equalWeightScorer());
        Topic topic = Topic.builder().id(7L).queryText("AI 모델")
                .optionalKeywords(List.of("LLM", "추론")).build();
        var prolific = batch(1L, topic, 11L,
                article("LLM 추론 소식 1", "https://example.com/news1", ""),
                article("LLM 추론 소식 2", "https://example.com/news2", ""));
        var original = batch(2L, topic, 12L,
                article("LLM 출시", "https://research.example.com/model", ""));

        var selected = prioritizer.prioritize(List.of(prolific, original));

        assertEquals(1, selected.get(1L).size());
        assertEquals(List.of("https://research.example.com/model"), urls(selected.get(2L)));
    }

    @Test
    void technologyArchiveWindowUsesDatesNotFeedOrderAndKeepsUnknownDates() {
        var properties = new CollectionPipelineProperties();
        properties.setTopicArticleLimit(5);
        var prioritizer = new CollectionCandidatePrioritizer(properties, equalWeightScorer());
        Topic topic = Topic.builder().id(7L).queryText("AI 모델").optionalKeywords(List.of("LLM")).build();
        OffsetDateTime now = OffsetDateTime.parse("2026-11-09T10:00:00+09:00");
        var items = batch(1L, topic, 11L,
                dated("https://example.com/archive", now.minusDays(31)),
                dated("https://example.com/new", now.minusDays(1)),
                dated("https://example.com/boundary", now.minusDays(30)),
                dated("https://example.com/unknown", null));

        assertEquals(List.of("https://example.com/new", "https://example.com/boundary",
                "https://example.com/unknown"), urls(prioritizer.prioritize(List.of(items), now).get(1L)));
        topic = Topic.builder().id(7L).queryText("AI 산업").optionalKeywords(List.of("LLM")).build();
        assertEquals(1, prioritizer.prioritize(List.of(batch(1L, topic, 11L,
                dated("https://example.com/archive", now.minusDays(31)))), now).get(1L).size());
    }

    private CollectedArticle dated(String url, OffsetDateTime date) {
        return new CollectedArticle("LLM 소식", url, "", date, "example.com", "ko");
    }

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
