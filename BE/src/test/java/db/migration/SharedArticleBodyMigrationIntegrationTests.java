package db.migration;

import com.example.be.BeApplication;
import com.example.be.domain.collection.entity.ArticleBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 같은 테스트 Oracle의 전용 레거시 테이블에서 CLOB 이관을 검증한다. */
@SpringBootTest(classes = BeApplication.class)
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SharedArticleBodyMigrationIntegrationTests {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    private String articles;
    private String versions;
    private String bodies;

    @BeforeEach
    void createLegacyTables() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        articles = "dedup_articles_" + suffix;
        versions = "dedup_versions_" + suffix;
        bodies = "dedup_bodies_" + suffix;
        jdbc.execute("CREATE TABLE " + bodies + " (body_hash VARCHAR2(64 CHAR) PRIMARY KEY, body CLOB NOT NULL)");
        for (String table : List.of(articles, versions)) {
            jdbc.execute("CREATE TABLE " + table + " (id NUMBER PRIMARY KEY, body CLOB, body_hash VARCHAR2(64 CHAR)"
                    + " REFERENCES " + bodies + " (body_hash))");
        }
    }

    @AfterEach
    void dropOwnedTables() {
        for (String table : List.of(articles, versions, bodies)) {
            jdbc.execute("DROP TABLE " + table + " PURGE");
        }
    }

    @Test
    void migratesCurrentAndVersionBodiesWithoutChangingAnyCharacterAndCanResume() throws Exception {
        String longBody = "한글 원문 😀 café e\u0301\r\n".repeat(5000);
        List<String> texts = List.of(longBody, longBody, longBody + " ", " \n\t\r ", "");
        for (int index = 0; index < texts.size(); index++) {
            insertLegacy(articles, index + 1, texts.get(index));
        }
        insertLegacy(versions, 1, longBody);
        insertLegacy(versions, 2, "수정 전 기사 본문");
        jdbc.update("INSERT INTO " + articles + " (id) VALUES (99)");

        migrate();
        migrate();

        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM " + bodies, Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM " + articles, Integer.class));
        for (int index = 0; index < texts.size(); index++) {
            assertEquals(texts.get(index), readBody(articles, index + 1));
            assertEquals(ArticleBody.of(texts.get(index)).getBodyHash(),
                    jdbc.queryForObject("SELECT body_hash FROM " + articles + " WHERE id = ?", String.class, index + 1));
        }
        assertEquals(longBody, readBody(versions, 1));
        assertEquals("수정 전 기사 본문", readBody(versions, 2));
        assertNull(readBody(articles, 99));
        for (String table : List.of(articles, versions)) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE body IS NOT NULL", Integer.class));
        }
    }

    @Test
    void refusesAHashCollisionWithoutClearingTheLegacyOriginal() throws Exception {
        String body = "보존해야 할 원문";
        insertLegacy(articles, 1, body);
        jdbc.update("INSERT INTO " + bodies + " (body_hash, body) VALUES (?, ?)",
                ArticleBody.of(body).getBodyHash(), "같은 해시 키에 잘못 저장된 다른 본문");

        assertThrows(SQLException.class, this::migrate);
        assertEquals(body, jdbc.queryForObject("SELECT body FROM " + articles + " WHERE id = 1", String.class));
        assertNull(jdbc.queryForObject("SELECT body_hash FROM " + articles + " WHERE id = 1", String.class));
    }

    @Test
    void installedSchemaHasReferencesAndNoPerArticleBodyColumns() {
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_tab_columns
                WHERE table_name IN ('NEWS_ARTICLES', 'NEWS_ARTICLE_VERSIONS') AND column_name = 'BODY'
                """, Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_tab_columns
                WHERE table_name IN ('NEWS_ARTICLES', 'NEWS_ARTICLE_VERSIONS') AND column_name = 'BODY_HASH'
                """, Integer.class));
    }

    @Test
    void resumesColumnRemovalAfterOnlyOneOracleDdlWasCommitted() throws Exception {
        insertLegacy(articles, 1, "같은 원문");
        insertLegacy(versions, 1, "같은 원문");
        migrate();
        jdbc.execute("ALTER TABLE " + articles + " DROP COLUMN body");

        jdbc.execute(removalSql());
        jdbc.execute(removalSql());

        assertEquals(0, legacyColumnCount());
        assertEquals("같은 원문", readBody(articles, 1));
        assertEquals("같은 원문", readBody(versions, 1));
    }

    @Test
    void refusesColumnRemovalWhileAnyOriginalIsUnmigrated() throws Exception {
        insertLegacy(versions, 1, "아직 이관하지 않은 원문");
        assertThrows(DataAccessException.class, () -> jdbc.execute(removalSql()));
        assertEquals(2, legacyColumnCount());
        assertEquals("아직 이관하지 않은 원문",
                jdbc.queryForObject("SELECT body FROM " + versions + " WHERE id = 1", String.class));
    }

    private int legacyColumnCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_tab_columns WHERE table_name IN (?, ?) AND column_name = 'BODY'",
                Integer.class, articles.toUpperCase(java.util.Locale.ROOT), versions.toUpperCase(java.util.Locale.ROOT));
    }

    private String removalSql() throws Exception {
        try (var stream = new ClassPathResource("db/migration/V50__remove_article_body_copies.sql").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("NEWS_ARTICLE_VERSIONS", versions.toUpperCase(java.util.Locale.ROOT))
                    .replace("NEWS_ARTICLES", articles.toUpperCase(java.util.Locale.ROOT))
                    .replaceAll("(?m)^/\\s*$", "");
        }
    }

    private void insertLegacy(String table, long id, String body) {
        if (body.isEmpty()) {
            jdbc.update("INSERT INTO " + table + " (id, body) VALUES (?, EMPTY_CLOB())", id);
            return;
        }
        jdbc.update("INSERT INTO " + table + " (id, body) VALUES (?, ?)", statement -> {
            statement.setLong(1, id);
            statement.setClob(2, new StringReader(body), (long) body.length());
        });
    }

    private String readBody(String table, long id) {
        return jdbc.queryForObject("SELECT b.body FROM " + table + " a LEFT JOIN " + bodies
                + " b ON b.body_hash = a.body_hash WHERE a.id = ?", String.class, id);
    }

    private void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                V49__backfill_shared_article_bodies.migrateTable(connection, articles, bodies);
                V49__backfill_shared_article_bodies.migrateTable(connection, versions, bodies);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
