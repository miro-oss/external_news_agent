package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * CLOB 전체를 SHA-256으로 식별하고 실제 문자열까지 비교한 뒤 기존 복사본을 비운다.
 * DBMS_CRYPTO 권한이나 VARCHAR2 길이 제한에 의존하지 않는다.
 * 애플리케이션 해시 구현이 바뀌어도 기존 이관 의미가 유지되도록 구현을 이 파일에 고정한다.
 * V48~V50은 기존 수집 프로세스를 중단한 상태에서 적용한다(구버전은 제거될 body 컬럼을 사용한다).
 */
public class V49__backfill_shared_article_bodies extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        return 1;
    }

    @Override
    public void migrate(Context context) throws Exception {
        migrateTable(context.getConnection(), "news_articles", "news_article_bodies");
        migrateTable(context.getConnection(), "news_article_versions", "news_article_bodies");
    }

    // 테이블 단위 이관은 같은 스키마의 격리된 레거시 fixture에서도 검증한다.
    static void migrateTable(Connection connection, String sourceTable, String bodyTable) throws Exception {
        requireIdentifier(sourceTable);
        requireIdentifier(bodyTable);
        try (PreparedStatement rows = connection.prepareStatement(
                "SELECT id, body, body_hash FROM " + sourceTable + " WHERE body IS NOT NULL ORDER BY id FOR UPDATE");
             PreparedStatement insert = connection.prepareStatement("""
                     BEGIN
                         INSERT INTO %s (body_hash, body) VALUES (?, ?);
                     EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                     END;
                     """.formatted(bodyTable));
             PreparedStatement insertEmpty = connection.prepareStatement("""
                     BEGIN
                         INSERT INTO %s (body_hash, body) VALUES (?, EMPTY_CLOB());
                     EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                     END;
                     """.formatted(bodyTable));
             PreparedStatement stored = connection.prepareStatement(
                     "SELECT body FROM " + bodyTable + " WHERE body_hash = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE " + sourceTable + " SET body_hash = ?, body = NULL WHERE id = ?")) {
            rows.setFetchSize(20);
            try (ResultSet result = rows.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong("id");
                    String body = result.getString("body");
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8)));
                    String existingHash = result.getString("body_hash");
                    if (existingHash != null && !hash.equals(existingHash)) {
                        throw new SQLException("Legacy body reference mismatch in " + sourceTable + ", id=" + id);
                    }
                    if (body.isEmpty()) {
                        insertEmpty.setString(1, hash);
                        insertEmpty.executeUpdate();
                    } else {
                        insert.setString(1, hash);
                        insert.setClob(2, new StringReader(body), (long) body.length());
                        insert.executeUpdate();
                    }
                    stored.setString(1, hash);
                    try (ResultSet saved = stored.executeQuery()) {
                        if (!saved.next() || !body.equals(saved.getString(1))) {
                            throw new SQLException("Shared body verification failed in " + sourceTable + ", id=" + id);
                        }
                    }
                    // 확인한 원문과 참조를 한 UPDATE로 교체해 재실행 시에도 원문이 유실되지 않는다.
                    update.setString(1, hash);
                    update.setLong(2, id);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Legacy article disappeared in " + sourceTable + ", id=" + id);
                    }
                }
            }
        }
    }

    private static void requireIdentifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid migration table identifier");
        }
    }
}
