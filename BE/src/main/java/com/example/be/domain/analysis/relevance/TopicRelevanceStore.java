package com.example.be.domain.analysis.relevance;

import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TopicRelevanceStore {
    private final JdbcTemplate jdbc;

    public List<Assessment> findByRun(Long runId) {
        return jdbc.query("SELECT * FROM news_topic_relevance WHERE run_id = ?", (rs, row) ->
                new Assessment(rs.getLong("run_id"), rs.getLong("topic_id"), rs.getLong("article_id"),
                        TopicRelevanceStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                        rs.getString("input_hash"), rs.getString("prompt_version"), rs.getString("model_name"),
                        rs.getString("evidence_quotes")), runId);
    }

    @Transactional
    public void saveAll(List<Assessment> assessments) {
        for (Assessment value : assessments) {
            jdbc.update("""
                    MERGE INTO news_topic_relevance target
                    USING (SELECT ? run_id, ? topic_id, ? article_id FROM dual) source
                    ON (target.run_id = source.run_id AND target.topic_id = source.topic_id
                        AND target.article_id = source.article_id)
                    WHEN MATCHED THEN UPDATE SET status = ?, reason = ?, input_hash = ?,
                        prompt_version = ?, model_name = ?, evidence_quotes = ?, assessed_at = ?
                    WHEN NOT MATCHED THEN INSERT
                        (run_id, topic_id, article_id, status, reason, input_hash, prompt_version,
                         model_name, evidence_quotes, assessed_at)
                        VALUES (source.run_id, source.topic_id, source.article_id, ?, ?, ?, ?, ?, ?, ?)
                    """, value.runId(), value.topicId(), value.articleId(),
                    value.status().name(), value.reason(), value.inputHash(), value.promptVersion(),
                    value.modelName(), value.evidenceQuotes(), LocalDateTime.now(ApiTimeZone.ZONE),
                    value.status().name(), value.reason(), value.inputHash(), value.promptVersion(),
                    value.modelName(), value.evidenceQuotes(), LocalDateTime.now(ApiTimeZone.ZONE));
        }
    }

    public List<DailyAssessment> latestOnDate(LocalDate date, Collection<Long> articleIds) {
        List<DailyAssessment> result = new ArrayList<>();
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (List<Long> batch : OracleInClause.batches(articleIds)) {
            result.addAll(named.query("""
                    SELECT * FROM (
                        SELECT r.article_id, r.topic_id, r.run_id, r.status, c.started_at,
                            ROW_NUMBER() OVER (PARTITION BY r.article_id, r.topic_id
                                ORDER BY c.started_at DESC, r.run_id DESC) position
                        FROM news_topic_relevance r JOIN news_collection_runs c ON c.id = r.run_id
                        WHERE c.started_at >= :fromDate AND c.started_at < :toDate
                            AND r.article_id IN (:articleIds)
                    ) WHERE position = 1
                    """, Map.of("fromDate", date.atStartOfDay(), "toDate", date.plusDays(1).atStartOfDay(),
                    "articleIds", batch), (rs, row) -> new DailyAssessment(
                            rs.getLong("article_id"), rs.getLong("topic_id"), rs.getLong("run_id"),
                            rs.getTimestamp("started_at").toLocalDateTime(),
                            TopicRelevanceStatus.valueOf(rs.getString("status")))));
        }
        return List.copyOf(result);
    }

    public record Assessment(Long runId, Long topicId, Long articleId, TopicRelevanceStatus status,
                             String reason, String inputHash, String promptVersion,
                             String modelName, String evidenceQuotes) {}
    public record DailyAssessment(Long articleId, Long topicId, Long runId,
                                  LocalDateTime startedAt, TopicRelevanceStatus status) {}
}
