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
import java.util.List;
import java.util.Locale;

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
        List<AgentKeywordStrategyRequest.ArticleObservation> articles = runArticleRepository
                .findKeywordStrategyObservations(runId, topicId)
                .stream()
                .sorted(Comparator
                        .comparing((CollectionRunArticle observation) -> observation.getArticle().getPublishedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CollectionRunArticle::getId, Comparator.reverseOrder()))
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
                keywordStats(topic, articles),
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
                article.getTitle(),
                truncate(article.getSummary(), MAX_ARTICLE_SUMMARY_LENGTH),
                publisher,
                observation.getChangeType().name(),
                article.getPublishedAt(),
                topicFit);
    }

    private List<AgentKeywordStrategyRequest.KeywordStat> keywordStats(
            Topic topic,
            List<AgentKeywordStrategyRequest.ArticleObservation> articles) {
        List<AgentKeywordStrategyRequest.KeywordStat> result = new ArrayList<>();
        addStats(result, TopicKeywordBucket.REQUIRED, topic.getRequiredKeywords(), articles);
        addStats(result, TopicKeywordBucket.OPTIONAL, topic.getOptionalKeywords(), articles);
        addStats(result, TopicKeywordBucket.EXCLUDED, topic.getExcludedKeywords(), articles);
        return List.copyOf(result);
    }

    private void addStats(List<AgentKeywordStrategyRequest.KeywordStat> sink,
                          TopicKeywordBucket bucket,
                          List<String> keywords,
                          List<AgentKeywordStrategyRequest.ArticleObservation> articles) {
        if (keywords == null) {
            return;
        }
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            int matchCount = (int) articles.stream()
                    .filter(article -> haystack(article).contains(normalized))
                    .count();
            sink.add(new AgentKeywordStrategyRequest.KeywordStat(
                    bucket.name(),
                    keyword,
                    matchCount));
        }
    }

    private String haystack(AgentKeywordStrategyRequest.ArticleObservation article) {
        return normalize(article.title() + " " + (article.summary() == null ? "" : article.summary()));
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
