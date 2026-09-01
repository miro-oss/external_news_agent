package com.example.be.domain.collection.scoring;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class KeywordIdfJdbcRepository {

    private static final String UPSERT_SQL = """
            MERGE INTO news_keyword_idf target
            USING (
                SELECT :languageCode AS language_code, :keywordValue AS keyword_value
                FROM dual
            ) source
            ON (
                target.language_code = source.language_code
                AND target.keyword_value = source.keyword_value
            )
            WHEN MATCHED THEN UPDATE SET
                target.document_count = :documentCount,
                target.document_frequency = :documentFrequency,
                target.idf_value = :idfValue,
                target.refreshed_at = :refreshedAt
            WHEN NOT MATCHED THEN INSERT (
                language_code,
                keyword_value,
                document_count,
                document_frequency,
                idf_value,
                refreshed_at
            ) VALUES (
                :languageCode,
                :keywordValue,
                :documentCount,
                :documentFrequency,
                :idfValue,
                :refreshedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CachedKeywordIdf> findAll(String language, Collection<String> keywords) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT language_code,
                               keyword_value,
                               document_count,
                               document_frequency,
                               idf_value,
                               refreshed_at
                        FROM news_keyword_idf
                        WHERE language_code = :language
                          AND keyword_value IN (:keywords)
                        """,
                Map.of("language", language, "keywords", keywords),
                (resultSet, rowNumber) -> new CachedKeywordIdf(
                        resultSet.getString("language_code"),
                        resultSet.getString("keyword_value"),
                        resultSet.getLong("document_count"),
                        resultSet.getLong("document_frequency"),
                        resultSet.getDouble("idf_value"),
                        resultSet.getTimestamp("refreshed_at").toLocalDateTime()));
    }

    public long countDocuments(String language, LocalDateTime collectedAfter) {
        Long count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM news_articles
                        WHERE LOWER(COALESCE(language, 'und')) = :language
                          AND collected_at >= :collectedAfter
                        """,
                Map.of("language", language, "collectedAfter", collectedAfter),
                Long.class);
        return count == null ? 0L : count;
    }

    public long countDocumentsContaining(String language,
                                         String keyword,
                                         LocalDateTime collectedAfter) {
        Long count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM news_articles
                        WHERE LOWER(COALESCE(language, 'und')) = :language
                          AND collected_at >= :collectedAfter
                          AND (
                              INSTR(LOWER(title), :keyword) > 0
                              OR DBMS_LOB.INSTR(LOWER(summary), :keyword) > 0
                          )
                        """,
                Map.of(
                        "language", language,
                        "keyword", keyword,
                        "collectedAfter", collectedAfter),
                Long.class);
        return count == null ? 0L : count;
    }

    @Transactional
    public void upsertAll(List<CachedKeywordIdf> values) {
        MapSqlParameterSource[] parameters = values.stream()
                .map(value -> new MapSqlParameterSource()
                        .addValue("languageCode", value.language())
                        .addValue("keywordValue", value.keyword())
                        .addValue("documentCount", value.documentCount())
                        .addValue("documentFrequency", value.documentFrequency())
                        .addValue("idfValue", value.idf())
                        .addValue("refreshedAt", value.refreshedAt()))
                .toArray(MapSqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(UPSERT_SQL, parameters);
    }

    public record CachedKeywordIdf(
            String language,
            String keyword,
            long documentCount,
            long documentFrequency,
            double idf,
            LocalDateTime refreshedAt
    ) {
    }
}
