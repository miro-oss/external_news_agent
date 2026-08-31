package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 필요한 연관을 값으로 복사한 뒤 영속성 컨텍스트를 닫아 계산 중 커넥션을 잡지 않게 한다. */
@Service
@RequiredArgsConstructor
public class IssueClusteringLoader {

    private final CollectionRunArticleRepository observationRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final IssueClusteringProperties properties;

    @Transactional(readOnly = true)
    public List<ClusterArticle> load(Long runId) {
        List<CollectionRunArticle> current = observationRepository.findClusterTargetsByRunId(runId);
        if (current.isEmpty()) {
            return List.of();
        }

        Set<Long> currentArticleIds = new LinkedHashSet<>();
        Set<Long> topicIds = new LinkedHashSet<>();
        OffsetDateTime earliest = null;
        for (CollectionRunArticle observation : current) {
            currentArticleIds.add(observation.getArticle().getId());
            topicIds.add(observation.getTopic().getId());
            OffsetDateTime eventTime = eventTime(observation.getArticle(), observation);
            earliest = earliest == null || eventTime.isBefore(earliest) ? eventTime : earliest;
        }

        Map<ArticleTopicKey, Long> issueByCurrentArticle = membershipsByArticleTopic(
                issueArticleRepository.findByArticleIds(currentArticleIds));
        OffsetDateTime since = earliest.minus(properties.getEntityTimeWindow());
        List<IssueArticle> historical = issueArticleRepository.findRecentByTopicIds(topicIds, since);

        Map<ArticleTopicKey, ClusterArticle> snapshot = new LinkedHashMap<>();
        for (IssueArticle membership : historical) {
            long topicId = membership.getIssue().getTopic().getId();
            if (!topicIds.contains(topicId)) {
                continue;
            }
            Article article = membership.getArticle();
            snapshot.put(new ArticleTopicKey(article.getId(), topicId),
                    value(article, membership.getIssue().getTopic(), membership.getIssue().getId(), false,
                            observedAt(article)));
        }
        for (CollectionRunArticle observation : current) {
            Article article = observation.getArticle();
            long topicId = observation.getTopic().getId();
            snapshot.put(new ArticleTopicKey(article.getId(), topicId),
                    value(article, observation.getTopic(),
                            issueByCurrentArticle.get(new ArticleTopicKey(article.getId(), topicId)),
                            true, observedAt(article)));
        }
        return List.copyOf(snapshot.values());
    }

    private Map<ArticleTopicKey, Long> membershipsByArticleTopic(Collection<IssueArticle> memberships) {
        Map<ArticleTopicKey, Long> result = new LinkedHashMap<>();
        memberships.forEach(membership -> result.merge(
                new ArticleTopicKey(
                        membership.getArticle().getId(), membership.getIssue().getTopic().getId()),
                membership.getIssue().getId(),
                Math::min));
        return Map.copyOf(result);
    }

    private ClusterArticle value(Article article,
                                 Topic topic,
                                 Long issueId,
                                 boolean observedInRun,
                                 OffsetDateTime observedAt) {
        return new ClusterArticle(
                article.getId(),
                topic.getId(),
                article.getTitle(),
                article.getSummary(),
                article.getBody(),
                article.getFetchStatus(),
                article.getSource().getId(),
                publisher(article),
                article.getSource().getReliabilityScore(),
                article.getPublishedAt(),
                observedAt,
                topicKeywords(topic),
                article.getContentGroup() == null ? null : article.getContentGroup().getId(),
                article.getContentGroup() == null ? null : article.getContentGroup().getSimhash(),
                issueId,
                observedInRun);
    }

    private List<String> topicKeywords(Topic topic) {
        List<String> keywords = new ArrayList<>();
        addAll(keywords, topic.getRequiredKeywords());
        addAll(keywords, topic.getOptionalKeywords());
        if (StringUtils.hasText(topic.getQueryText())) {
            keywords.addAll(TitleTokenizer.tokens(topic.getQueryText()));
        }
        return List.copyOf(new LinkedHashSet<>(keywords));
    }

    private void addAll(List<String> target, List<String> values) {
        if (values != null) {
            values.stream().filter(StringUtils::hasText).map(String::trim).forEach(target::add);
        }
    }

    private String publisher(Article article) {
        return StringUtils.hasText(article.getSourceName())
                ? article.getSourceName().trim()
                : article.getSource().getName();
    }

    private OffsetDateTime eventTime(Article article, CollectionRunArticle observation) {
        return article.getPublishedAt() == null
                ? observationTime(observation)
                : article.getPublishedAt();
    }

    private OffsetDateTime observationTime(CollectionRunArticle observation) {
        return observation.getObservedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private OffsetDateTime observedAt(Article article) {
        if (article.getCollectedAt() == null) {
            return article.getPublishedAt();
        }
        return article.getCollectedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private record ArticleTopicKey(long articleId, long topicId) {
    }
}
