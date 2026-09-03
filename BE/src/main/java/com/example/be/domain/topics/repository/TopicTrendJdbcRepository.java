package com.example.be.domain.topics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주제별 최근 7일 키워드 신호를 Oracle 집계로 계산한다.
 *
 * <p>P2-8은 LLM 없이 "무엇이 최근에 늘었는지 / 무엇이 이 주제와 자주 같이 나오는지"만 보여 주면 된다.
 * 기사 본문을 다시 파싱하지 않고, 이미 저장된 issue 엔티티와 run 관측 이력을 SQL로만 묶는다.
 */
@Repository
@RequiredArgsConstructor
public class TopicTrendJdbcRepository {

    private static final int WINDOW_DAYS = 7;
    private static final int CANDIDATE_LIMIT = 12;

    private static final String OBSERVED_ISSUE_KEYWORDS_CTE = """
            WITH observed_issues AS (
                SELECT cra.topic_id,
                       ia.issue_id,
                       TRUNC(cra.observed_at) AS activity_day
                FROM news_collection_run_articles cra
                JOIN news_issue_articles ia
                  ON ia.article_id = cra.article_id
                JOIN news_issues issue
                  ON issue.id = ia.issue_id
                WHERE cra.topic_id IN (:topicIds)
                  AND cra.change_type IN ('NEW', 'UPDATED')
                  AND cra.observed_at >= :previousWindowStart
                  AND cra.observed_at < :windowEndExclusive
                  AND issue.status <> 'RETRACTED'
                GROUP BY cra.topic_id,
                         ia.issue_id,
                         TRUNC(cra.observed_at)
            ),
            observed_issue_keywords AS (
                SELECT observed.topic_id,
                       observed.issue_id,
                       observed.activity_day,
                       LOWER(TRIM(jt.keyword)) AS keyword_norm,
                       MIN(TRIM(jt.keyword)) AS keyword_display
                FROM observed_issues observed
                JOIN news_issues issue
                  ON issue.id = observed.issue_id
                CROSS APPLY JSON_TABLE(
                    issue.entities,
                    '$[*]' COLUMNS (
                        keyword VARCHAR2(500 CHAR) PATH '$'
                    )
                ) jt
                WHERE TRIM(jt.keyword) IS NOT NULL
                GROUP BY observed.topic_id,
                         observed.issue_id,
                         observed.activity_day,
                         LOWER(TRIM(jt.keyword))
            )
            """;

    private static final String SURGE_SQL = OBSERVED_ISSUE_KEYWORDS_CTE + """
            , keyword_window_counts AS (
                SELECT topic_id,
                       keyword_norm,
                       MIN(keyword_display) AS keyword_display,
                       COUNT(DISTINCT CASE
                           WHEN activity_day >= :recentWindowStartDate THEN issue_id
                       END) AS recent_issue_count,
                       COUNT(DISTINCT CASE
                           WHEN activity_day < :recentWindowStartDate THEN issue_id
                       END) AS previous_issue_count
                FROM observed_issue_keywords
                GROUP BY topic_id, keyword_norm
            ),
            keywords AS (
                SELECT DISTINCT topic_id, keyword_norm, keyword_display
                FROM observed_issue_keywords
            ),
            days AS (
                SELECT :previousWindowStartDate + (LEVEL - 1) AS activity_day
                FROM dual
                CONNECT BY LEVEL <= :seriesDayCount
            ),
            daily_counts AS (
                SELECT topic_id,
                       keyword_norm,
                       activity_day,
                       COUNT(DISTINCT issue_id) AS issue_count
                FROM observed_issue_keywords
                GROUP BY topic_id, keyword_norm, activity_day
            ),
            daily_series AS (
                SELECT k.topic_id,
                       k.keyword_norm,
                       k.keyword_display,
                       d.activity_day,
                       COALESCE(dc.issue_count, 0) AS issue_count
                FROM keywords k
                CROSS JOIN days d
                LEFT JOIN daily_counts dc
                  ON dc.topic_id = k.topic_id
                 AND dc.keyword_norm = k.keyword_norm
                 AND dc.activity_day = d.activity_day
            ),
            keyword_daily_summary AS (
                SELECT topic_id,
                       keyword_norm,
                       MIN(keyword_display) AS keyword_display,
                       MAX(CASE
                           WHEN activity_day >= :recentWindowStartDate THEN issue_count
                           ELSE 0
                       END) AS peak_issue_count,
                       AVG(CASE
                           WHEN activity_day < :recentWindowStartDate THEN issue_count
                       END) AS previous_daily_avg,
                       STDDEV_POP(CASE
                           WHEN activity_day < :recentWindowStartDate THEN issue_count
                       END) AS previous_daily_stddev
                FROM daily_series
                GROUP BY topic_id, keyword_norm
            ),
            ranked AS (
                SELECT wc.topic_id,
                       wc.keyword_display AS keyword,
                       wc.recent_issue_count,
                       wc.previous_issue_count,
                       wc.recent_issue_count - wc.previous_issue_count AS delta_issue_count,
                       CASE
                           WHEN ds.previous_daily_stddev > 0
                               THEN ROUND((ds.peak_issue_count - ds.previous_daily_avg)
                                       / ds.previous_daily_stddev, 2)
                           ELSE NULL
                       END AS z_score,
                       CASE
                           WHEN ds.previous_daily_stddev > 0
                                AND (ds.peak_issue_count - ds.previous_daily_avg)
                                    / ds.previous_daily_stddev >= 2.5
                               THEN 1
                           ELSE 0
                       END AS burst_flag,
                       ROW_NUMBER() OVER (
                           PARTITION BY wc.topic_id
                           ORDER BY CASE
                                        WHEN ds.previous_daily_stddev > 0
                                             AND (ds.peak_issue_count - ds.previous_daily_avg)
                                                 / ds.previous_daily_stddev >= 2.5
                                            THEN 1
                                        ELSE 0
                                    END DESC,
                                    CASE
                                        WHEN ds.previous_daily_stddev > 0
                                            THEN ROUND((ds.peak_issue_count - ds.previous_daily_avg)
                                                    / ds.previous_daily_stddev, 2)
                                        ELSE NULL
                                    END DESC NULLS LAST,
                                    wc.recent_issue_count - wc.previous_issue_count DESC,
                                    wc.recent_issue_count DESC,
                                    wc.keyword_display ASC
                       ) AS rn
                FROM keyword_window_counts wc
                JOIN keyword_daily_summary ds
                  ON ds.topic_id = wc.topic_id
                 AND ds.keyword_norm = wc.keyword_norm
                WHERE wc.recent_issue_count >= 2
                  AND wc.recent_issue_count > wc.previous_issue_count
            )
            SELECT topic_id,
                   keyword,
                   recent_issue_count,
                   previous_issue_count,
                   delta_issue_count,
                   z_score,
                   burst_flag
            FROM ranked
            WHERE rn <= :candidateLimit
            ORDER BY topic_id ASC, rn ASC
            """;

    private static final String RELATED_SQL = OBSERVED_ISSUE_KEYWORDS_CTE + """
            , recent_issue_keywords AS (
                SELECT topic_id,
                       issue_id,
                       keyword_norm,
                       MIN(keyword_display) AS keyword_display
                FROM observed_issue_keywords
                WHERE activity_day >= :recentWindowStartDate
                GROUP BY topic_id, issue_id, keyword_norm
            ),
            topic_totals AS (
                SELECT topic_id,
                       COUNT(DISTINCT issue_id) AS total_issue_count
                FROM observed_issues
                WHERE activity_day >= :recentWindowStartDate
                GROUP BY topic_id
            ),
            topic_keyword_counts AS (
                SELECT topic_id,
                       keyword_norm,
                       MIN(keyword_display) AS keyword_display,
                       COUNT(DISTINCT issue_id) AS issue_count
                FROM recent_issue_keywords
                GROUP BY topic_id, keyword_norm
            ),
            ranked AS (
                SELECT tk.topic_id,
                       tk.keyword_display AS keyword,
                       tk.issue_count,
                       ROUND((tk.issue_count * 100.0) / tt.total_issue_count, 2) AS share_percent,
                       ROW_NUMBER() OVER (
                           PARTITION BY tk.topic_id
                           ORDER BY tk.issue_count DESC,
                                    ROUND((tk.issue_count * 100.0) / tt.total_issue_count, 2) DESC,
                                    tk.keyword_display ASC
                       ) AS rn
                FROM topic_keyword_counts tk
                JOIN topic_totals tt
                  ON tt.topic_id = tk.topic_id
                WHERE tk.issue_count >= 2
            )
            SELECT topic_id,
                   keyword,
                   issue_count,
                   share_percent
            FROM ranked
            WHERE rn <= :candidateLimit
            ORDER BY topic_id ASC, rn ASC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<Long, TopicTrendSnapshot> findSnapshots(Collection<Long> topicIds, LocalDateTime now) {
        if (topicIds == null || topicIds.isEmpty()) {
            return Map.of();
        }

        LocalDate today = now.toLocalDate();
        LocalDate recentWindowStartDate = today.minusDays(WINDOW_DAYS - 1L);
        LocalDate previousWindowStartDate = recentWindowStartDate.minusDays(WINDOW_DAYS);
        LocalDateTime previousWindowStart = previousWindowStartDate.atStartOfDay();
        LocalDateTime windowEndExclusive = today.plusDays(1L).atStartOfDay();

        Map<Long, SnapshotBuilder> snapshots = new LinkedHashMap<>();
        topicIds.forEach(topicId -> snapshots.put(topicId, new SnapshotBuilder()));

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("topicIds", topicIds)
                .addValue("recentWindowStartDate", recentWindowStartDate)
                .addValue("previousWindowStart", previousWindowStart)
                .addValue("previousWindowStartDate", previousWindowStartDate)
                .addValue("windowEndExclusive", windowEndExclusive)
                .addValue("seriesDayCount", WINDOW_DAYS * 2)
                .addValue("candidateLimit", CANDIDATE_LIMIT);

        jdbcTemplate.query(SURGE_SQL, parameters, (RowCallbackHandler) resultSet -> snapshots.get(resultSet.getLong("topic_id"))
                .surgeKeywords
                .add(new TopicTrendKeyword(
                        resultSet.getString("keyword"),
                        resultSet.getInt("recent_issue_count"),
                        resultSet.getInt("previous_issue_count"),
                        resultSet.getInt("delta_issue_count"),
                        resultSet.getBigDecimal("z_score"),
                        resultSet.getInt("burst_flag") == 1
                )));

        jdbcTemplate.query(RELATED_SQL, parameters, (RowCallbackHandler) resultSet -> snapshots.get(resultSet.getLong("topic_id"))
                .relatedKeywords
                .add(new TopicRelatedKeyword(
                        resultSet.getString("keyword"),
                        resultSet.getInt("issue_count"),
                        resultSet.getBigDecimal("share_percent")
                )));

        Map<Long, TopicTrendSnapshot> result = new LinkedHashMap<>();
        snapshots.forEach((topicId, builder) -> result.put(topicId, builder.build()));
        return result;
    }

    private static final class SnapshotBuilder {

        private final List<TopicTrendKeyword> surgeKeywords = new ArrayList<>();
        private final List<TopicRelatedKeyword> relatedKeywords = new ArrayList<>();

        private TopicTrendSnapshot build() {
            return new TopicTrendSnapshot(
                    List.copyOf(surgeKeywords),
                    List.copyOf(relatedKeywords)
            );
        }
    }

    public record TopicTrendSnapshot(
            List<TopicTrendKeyword> surgeKeywords,
            List<TopicRelatedKeyword> relatedKeywords
    ) {
    }

    public record TopicTrendKeyword(
            String keyword,
            int issueCount,
            int previousIssueCount,
            int deltaIssueCount,
            BigDecimal zScore,
            boolean burst
    ) {
    }

    public record TopicRelatedKeyword(
            String keyword,
            int issueCount,
            BigDecimal sharePercent
    ) {
    }
}
