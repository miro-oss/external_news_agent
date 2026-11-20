package com.example.be.domain.topics.repository;

import com.example.be.domain.collection.cluster.DeterministicEntityExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 주제에서 실제 수집한 기사 자체의 키워드를 같은 길이의 최근/직전 168시간으로 비교한다. */
@Repository
@RequiredArgsConstructor
public class TopicTrendJdbcRepository {

    private static final String OBSERVED_ARTICLES_SQL = """
            WITH observed_articles AS (
                SELECT cra.topic_id,
                       cra.article_id,
                       MIN(cra.observed_at) AS first_observed_at
                FROM news_collection_run_articles cra
                WHERE cra.topic_id IN (:topicIds)
                  AND cra.change_type IN ('NEW', 'UPDATED')
                  AND cra.observed_at >= :previousWindowStart
                  AND cra.observed_at < :windowEndExclusive
                GROUP BY cra.topic_id, cra.article_id,
                         CASE WHEN cra.observed_at >= :recentWindowStart THEN 1 ELSE 0 END
            )
            SELECT observed.topic_id, observed.article_id, observed.first_observed_at,
                   article.title, article.summary, stored_body.body
            FROM observed_articles observed
            JOIN news_articles article ON article.id = observed.article_id
            JOIN news_article_bodies stored_body ON stored_body.body_hash = article.body_hash
            WHERE article.fetch_status = 'FULLTEXT'
              AND REGEXP_INSTR(stored_body.body, '[^[:space:]]') > 0
            ORDER BY observed.article_id, observed.topic_id, observed.first_observed_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<Long, TopicTrendSnapshot> findSnapshots(Collection<Long> topicIds, LocalDateTime now) {
        if (topicIds == null || topicIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, TopicArticleTrendAccumulator> snapshots = new LinkedHashMap<>();
        topicIds.forEach(topicId -> snapshots.put(topicId, new TopicArticleTrendAccumulator(now)));
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("topicIds", topicIds)
                .addValue("recentWindowStart", now.minusDays(7))
                .addValue("previousWindowStart", now.minusDays(14))
                .addValue("windowEndExclusive", now);

        // Ordered streaming keeps only one article's extracted names in memory. The same shared
        // article is parsed once even if observed by several topics or in both comparison windows.
        DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();
        jdbcTemplate.query(OBSERVED_ARTICLES_SQL, parameters, new RowCallbackHandler() {
            private long lastArticleId = Long.MIN_VALUE;
            private Set<String> keywords = Set.of();

            @Override
            public void processRow(java.sql.ResultSet row) throws java.sql.SQLException {
                long articleId = row.getLong("article_id");
                if (articleId != lastArticleId) {
                    String body = row.getString("body");
                    keywords = new LinkedHashSet<>(extractor.extract(row.getString("title"), row.getString("summary"),
                            body, List.of()));
                    // Mention volume also includes organizations appearing only in the body. The
                    // stricter headline-only organization rule remains unchanged for issue clustering.
                    keywords.addAll(extractor.extractProse(body, Set.of()));
                    keywords = extractor.canonicalizeEntities(keywords);
                    lastArticleId = articleId;
                }
                snapshots.get(row.getLong("topic_id"))
                        .observe(row.getTimestamp("first_observed_at").toLocalDateTime(), keywords);
            }
        });

        Map<Long, TopicTrendSnapshot> result = new LinkedHashMap<>();
        // Do not truncate candidates here: configured keywords are excluded by the service before
        // its display limit, so even many configured names cannot hide the remaining candidates.
        snapshots.forEach((topicId, snapshot) -> result.put(topicId, snapshot.build()));
        return result;
    }

    public record TopicTrendSnapshot(
            List<TopicTrendKeyword> surgeKeywords,
            List<TopicRelatedKeyword> relatedKeywords
    ) {
    }

    public record TopicTrendKeyword(
            String keyword,
            int articleCount,
            int previousArticleCount,
            int deltaArticleCount,
            BigDecimal zScore,
            boolean burst
    ) {
    }

    public record TopicRelatedKeyword(
            String keyword,
            int articleCount,
            BigDecimal sharePercent
    ) {
    }
}
