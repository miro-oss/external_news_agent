package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicKeywordStrategyInputAssemblerTest {

    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final CollectionRunArticleRepository runArticleRepository =
            mock(CollectionRunArticleRepository.class);
    private final TopicFitScorer topicFitScorer = mock(TopicFitScorer.class);
    private final TopicRelevancePolicy relevancePolicy = mock(TopicRelevancePolicy.class);
    private final TopicKeywordStrategyInputAssembler assembler =
            new TopicKeywordStrategyInputAssembler(topicRepository, runArticleRepository, topicFitScorer, relevancePolicy);

    @Test
    void countsAllDistinctObservationsBeforeLimitingAgentArticles() {
        Topic topic = Topic.builder()
                .id(7L)
                .name("HBM")
                .requiredKeywords(List.of("HBM", " hbm "))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .build();
        List<CollectionRunArticle> observations = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T12:00:00+09:00");
        for (int index = 0; index < 21; index++) {
            String title = index == 20 ? "오래된 HBM 기사" : "일반 기사 " + index;
            Article article = Article.builder()
                    .id((long) index + 1)
                    .title(title)
                    .summary("요약")
                    .sourceName("테스트 매체")
                    .publishedAt(now.minusHours(index))
                    .build();
            observations.add(CollectionRunArticle.builder()
                    .id((long) index + 1)
                    .article(article)
                    .changeType(ChangeType.NEW)
                    .build());
        }
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));
        when(runArticleRepository.findKeywordStrategyObservations(42L, 7L))
                .thenReturn(observations);

        TopicKeywordStrategyInputAssembler.Snapshot snapshot = assembler.assemble(42L, 7L);

        assertThat(snapshot.articles()).hasSize(20);
        assertThat(snapshot.articles()).noneMatch(article -> article.title().contains("HBM"));
        assertThat(snapshot.currentKeywordStats()).singleElement().satisfies(stat -> {
            assertThat(stat.keyword()).isEqualTo("HBM");
            assertThat(stat.articleMatchCount()).isEqualTo(1);
        });
    }
    @Test
    void excludesRejectedObservationsFromKeywordStatsAndAgentInputWithinTheObservedTopic() {
        Topic topic = Topic.builder().id(7L).name("반도체 제조 장비")
                .requiredKeywords(List.of("공정")).optionalKeywords(List.of()).excludedKeywords(List.of()).build();
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));
        when(runArticleRepository.findKeywordStrategyObservations(42L, 7L)).thenReturn(List.of(
                observation(1L, "공정위 토스 분쟁"), observation(2L, "관련성 판단 보류 기사"),
                observation(3L, "반도체 공정 장비 증설")));
        when(relevancePolicy.excludedKeys(42L)).thenReturn(Set.of(
                new TopicRelevanceGate.Key(1L, 7L), new TopicRelevanceGate.Key(2L, 7L),
                new TopicRelevanceGate.Key(3L, 8L)));

        TopicKeywordStrategyInputAssembler.Snapshot snapshot = assembler.assemble(42L, 7L);

        assertThat(snapshot.articles()).extracting(article -> article.articleId()).containsExactly(3L);
        assertThat(snapshot.currentKeywordStats()).singleElement().satisfies(stat ->
                assertThat(stat.articleMatchCount()).isEqualTo(1));
    }

    private CollectionRunArticle observation(Long articleId, String title) {
        return CollectionRunArticle.builder().id(articleId).article(Article.builder()
                        .id(articleId).title(title).summary("요약").build())
                .changeType(ChangeType.NEW).build();
    }

}
