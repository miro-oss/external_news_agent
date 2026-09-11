package com.example.be.domain.collection.cluster;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded event identity families share one time limit and conflict contract. */
final class StrongEventEvidence {
    private final SpecificEventEvidence specific;
    private final ProductEventEvidence product;
    private final NamedEventEvidence named;
    private final StockEventEvidence stock;
    private final Map<Long, OffsetDateTime> times = new HashMap<>();

    StrongEventEvidence(List<ClusterArticle> articles, BreakingNewsDetector detector) {
        specific = new SpecificEventEvidence(articles, detector);
        product = new ProductEventEvidence(articles, detector);
        named = new NamedEventEvidence(articles, detector);
        stock = new StockEventEvidence(articles, detector);
        articles.forEach(article -> times.put(article.articleId(), article.eventTime()));
    }

    boolean matches(long left, long right) {
        OffsetDateTime first = times.get(left);
        OffsetDateTime second = times.get(right);
        if (first == null || second == null
                || Duration.between(first, second).abs().compareTo(Duration.ofHours(48)) > 0
                || conflicts(left, right)) {
            return false;
        }
        return specific.matches(left, right) || product.matches(left, right)
                || named.matches(left, right) || stock.matches(left, right);
    }

    boolean conflicts(long left, long right) {
        return specific.conflicts(left, right) || product.conflicts(left, right)
                || named.conflicts(left, right) || stock.conflicts(left, right);
    }
}
