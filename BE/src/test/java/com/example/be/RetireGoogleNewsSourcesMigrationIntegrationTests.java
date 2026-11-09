package com.example.be;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class RetireGoogleNewsSourcesMigrationIntegrationTests {

    private static final String SCRIPT = "db/migration/V83__retire_google_news_sources.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void retiresSeededGoogleNewsWithoutDeletingItsCatalogOrGoogleResearch() {
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM "flyway_schema_history" WHERE "version" = '83' AND "success" = 1
                """, Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM news_sources
                WHERE source_kind = 'FEED' AND url_template LIKE 'https://news.google.com/%'
                  AND active_yn = 'N'
                """, Integer.class));
        assertEquals("N", active(sourceId("SEARCH", "SERPAPI")));
        assertEquals("Y", active(sourceId("FEED", "https://research.google/blog/rss/")));
        assertEquals("Y", active(sourceId("SEARCH", "NAVER")));
    }

    @Test
    void rerunningRetirementOnlyUnlinksTargetSourcesAndPreservesAllHistoricalRows() {
        String marker = "retire-google-" + UUID.randomUUID();
        Long googleNews = insertSource("HTTPS://NEWS.GOOGLE.COM:443/rss/search?q=" + marker, marker);
        Long serpApi = sourceId("SEARCH", "SERPAPI");
        jdbcTemplate.update("UPDATE news_sources SET active_yn = 'Y' WHERE id = ?", serpApi);
        List<Long> preserved = List.of(
                sourceId("FEED", "https://research.google/blog/rss/"), sourceId("SEARCH", "NAVER"),
                insertSource("https://news.google.com.example.test/rss/" + marker, marker + " host"),
                insertSource("https://example.test/news.google.com/rss/" + marker, marker + " path"));
        jdbcTemplate.update("INSERT INTO news_topics (name) VALUES (?)", marker);
        Long topic = jdbcTemplate.queryForObject("SELECT id FROM news_topics WHERE name = ?", Long.class, marker);
        for (Long source : List.of(googleNews, serpApi)) link(topic, source);
        preserved.forEach(source -> link(topic, source));

        jdbcTemplate.update("""
                INSERT INTO news_collection_runs (status, trigger_type, idempotency_key)
                VALUES ('SUCCESS', 'MANUAL', ?)
                """, marker);
        Long run = jdbcTemplate.queryForObject(
                "SELECT id FROM news_collection_runs WHERE idempotency_key = ?", Long.class, marker);
        jdbcTemplate.update("""
                INSERT INTO news_collection_run_items (run_id, topic_id, source_id, status, scanned_count, new_count)
                VALUES (?, ?, ?, 'SUCCESS', 1, 1)
                """, run, topic, googleNews);
        String articleUrl = "https://publisher.example/" + marker;
        jdbcTemplate.update("""
                INSERT INTO news_articles (topic_id, source_id, url_hash, canonical_url, title, fetch_status,
                                           first_seen_run_id, last_seen_run_id)
                VALUES (?, ?, ?, ?, ?, 'METADATA_ONLY', ?, ?)
                """, topic, googleNews, UUID.randomUUID().toString().replace("-", ""), articleUrl, marker, run, run);
        Long article = jdbcTemplate.queryForObject(
                "SELECT id FROM news_articles WHERE canonical_url = ?", Long.class, articleUrl);
        jdbcTemplate.update("""
                INSERT INTO news_collection_run_articles (run_id, article_id, topic_id, source_id, change_type)
                VALUES (?, ?, ?, ?, 'NEW')
                """, run, article, topic, googleNews);
        Long sourcesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM news_sources", Long.class);

        retire();
        retire();

        assertEquals(sourcesBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM news_sources", Long.class));
        for (Long source : List.of(googleNews, serpApi)) {
            assertEquals("N", active(source));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM news_topic_sources WHERE source_id = ?", Integer.class, source));
        }
        for (Long source : preserved) {
            assertEquals("Y", active(source));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM news_topic_sources WHERE topic_id = ? AND source_id = ?",
                    Integer.class, topic, source));
        }
        assertEquals(marker, jdbcTemplate.queryForObject(
                "SELECT name FROM news_sources WHERE id = ?", String.class, googleNews));
        assertEquals("SUCCESS", jdbcTemplate.queryForObject(
                "SELECT status FROM news_collection_runs WHERE id = ?", String.class, run));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT scanned_count FROM news_collection_run_items WHERE run_id = ? AND source_id = ?
                """, Integer.class, run, googleNews));
        assertEquals(articleUrl, jdbcTemplate.queryForObject(
                "SELECT canonical_url FROM news_articles WHERE id = ? AND source_id = ?",
                String.class, article, googleNews));
        assertEquals("NEW", jdbcTemplate.queryForObject("""
                SELECT change_type FROM news_collection_run_articles
                WHERE run_id = ? AND article_id = ? AND source_id = ?
                """, String.class, run, article, googleNews));
    }

    private Long insertSource(String url, String name) {
        jdbcTemplate.update("""
                INSERT INTO news_sources (source_kind, name, url_template, active_yn)
                VALUES ('FEED', ?, ?, 'Y')
                """, name, url);
        return sourceId("FEED", url);
    }

    private Long sourceId(String kind, String url) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM news_sources WHERE source_kind = ? AND url_template = ?", Long.class, kind, url);
    }

    private String active(Long source) {
        return jdbcTemplate.queryForObject("SELECT active_yn FROM news_sources WHERE id = ?", String.class, source);
    }

    private void link(Long topic, Long source) {
        jdbcTemplate.update("INSERT INTO news_topic_sources (topic_id, source_id) VALUES (?, ?)", topic, source);
    }

    private void retire() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));
            return null;
        });
    }
}
