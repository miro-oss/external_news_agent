package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TopicKeywordStrategyInputAssembler {

    private static final int MAX_ARTICLES = 20;
    private static final int MAX_ARTICLE_SUMMARY_LENGTH = 2_000;

    private final TopicRepository topicRepository;
    private final CollectionRunArticleRepository runArticleRepository;
    private final TopicFitScorer topicFitScorer;

    public Snapshot assemble(Long runId, Long topicId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));
        List<CollectionRunArticle> observations = runArticleRepository
                .findKeywordStrategyObservations(runId, topicId)
                .stream()
                .sorted(Comparator
                        .comparing((CollectionRunArticle observation) -> observation.getArticle().getPublishedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CollectionRunArticle::getId, Comparator.reverseOrder()))
                .collect(LinkedHashMap<Long, CollectionRunArticle>::new,
                        (byArticleId, observation) -> byArticleId.putIfAbsent(
                                observation.getArticle().getId(), observation),
                        Map::putAll)
                .values()
                .stream()
                .toList();
        List<AgentKeywordStrategyRequest.ArticleObservation> articles = observations.stream()
                .limit(MAX_ARTICLES)
                .map(observation -> article(topic, observation))
                .toList();
        return new Snapshot(
                new AgentKeywordStrategyRequest.Topic(
                        topic.getName(),
                        topic.getQueryText(),
                        topic.getRequiredKeywords(),
                        topic.getOptionalKeywords(),
                        topic.getExcludedKeywords()),
                keywordStats(topic, observations),
                articles);
    }

    private AgentKeywordStrategyRequest.ArticleObservation article(Topic topic,
                                                                   CollectionRunArticle observation) {
        var article = observation.getArticle();
        BigDecimal topicFit = BigDecimal.valueOf(topicFitScorer.score(
                        topic,
                        article.getTitle(),
                        article.getSummary(),
                        article.getLanguage(),
                        article.getSource() == null ? null : article.getSource().getLanguage()))
                .setScale(4, RoundingMode.HALF_UP);
        String publisher = StringUtils.hasText(article.getSourceName())
                ? article.getSourceName().trim()
                : article.getSource() == null ? null : article.getSource().getName();
        return new AgentKeywordStrategyRequest.ArticleObservation(
                article.getId(),
                truncate(article.getTitle(), 1_000),
                truncate(article.getSummary(), MAX_ARTICLE_SUMMARY_LENGTH),
                truncate(publisher, 500),
                observation.getChangeType().name(),
                article.getPublishedAt(),
                topicFit);
    }

    private List<AgentKeywordStrategyRequest.KeywordStat> keywordStats(
            Topic topic,
            List<CollectionRunArticle> observations) {
        List<String> articleHaystacks = observations.stream()
                .map(this::haystack)
                .toList();
        List<AgentKeywordStrategyRequest.KeywordStat> result = new ArrayList<>();
        addStats(result, TopicKeywordBucket.REQUIRED, topic.getRequiredKeywords(), articleHaystacks);
        addStats(result, TopicKeywordBucket.OPTIONAL, topic.getOptionalKeywords(), articleHaystacks);
        addStats(result, TopicKeywordBucket.EXCLUDED, topic.getExcludedKeywords(), articleHaystacks);
        return List.copyOf(result);
    }

    private void addStats(List<AgentKeywordStrategyRequest.KeywordStat> sink,
                          TopicKeywordBucket bucket,
                          List<String> keywords,
                          List<String> articleHaystacks) {
        if (keywords == null) {
            return;
        }
        Map<String, String> uniqueKeywords = new LinkedHashMap<>();
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            String normalized = normalize(keyword);
            uniqueKeywords.putIfAbsent(normalized, keyword.trim());
        }
        for (Map.Entry<String, String> keyword : uniqueKeywords.entrySet()) {
            int matchCount = (int) articleHaystacks.stream()
                    .filter(haystack -> haystack.contains(keyword.getKey()))
                    .count();
            sink.add(new AgentKeywordStrategyRequest.KeywordStat(
                    bucket.name(),
                    keyword.getValue(),
                    matchCount));
        }
    }

    private String haystack(CollectionRunArticle observation) {
        var article = observation.getArticle();
        return normalize(article.getTitle() + " " + (article.getSummary() == null ? "" : article.getSummary()));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    public record Snapshot(
            AgentKeywordStrategyRequest.Topic topic,
            List<AgentKeywordStrategyRequest.KeywordStat> currentKeywordStats,
            List<AgentKeywordStrategyRequest.ArticleObservation> articles
    ) {
    }
}
