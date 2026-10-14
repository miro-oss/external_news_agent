package com.example.be.domain.dart.company;

import com.example.be.domain.dart.model.DartModels;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DartCompanyRepository {
    private final JdbcTemplate jdbc;

    boolean hasCompanies() {
        return !jdbc.query("SELECT corp_code FROM news_dart_companies FETCH FIRST 1 ROW ONLY",
                (rs, row) -> rs.getString(1)).isEmpty();
    }

    public Optional<DartModels.Company> find(String code) {
        return jdbc.query("SELECT corp_code, corp_name, stock_code FROM news_dart_companies WHERE corp_code = ?",
                (rs, row) -> new DartModels.Company(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? "" : rs.getString(3)), code).stream().findFirst();
    }

    long count(String query) {
        query = query.replace(" ", "");
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM news_dart_companies
                WHERE INSTR(REPLACE(UPPER(corp_name), ' ', ''), UPPER(?)) > 0 OR INSTR(stock_code, ?) > 0
                """, Long.class, query, query);
        return total == null ? 0 : total;
    }

    List<DartModels.Company> search(String query, int page, int size) {
        query = query.replace(" ", "");
        return jdbc.query("""
                SELECT corp_code, corp_name, stock_code FROM news_dart_companies
                WHERE INSTR(REPLACE(UPPER(corp_name), ' ', ''), UPPER(?)) > 0 OR INSTR(stock_code, ?) > 0
                ORDER BY CASE WHEN REPLACE(UPPER(corp_name), ' ', '') = UPPER(?) OR stock_code = ? THEN 0 ELSE 1 END,
                  CASE WHEN stock_code IS NOT NULL THEN 0 ELSE 1 END, corp_name, corp_code
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, row) -> new DartModels.Company(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? "" : rs.getString(3)),
                query, query, query, query, (long) page * size, size);
    }

    /** Readers retain the last committed corpus until the entire validated replacement commits. */
    @Transactional
    public void replace(List<DartCompanyArchive.Entry> entries) {
        if (entries.isEmpty()) throw new IllegalArgumentException("Cannot replace the DART corpus with an empty list");
        jdbc.update("DELETE FROM news_dart_companies");
        jdbc.batchUpdate("""
                INSERT INTO news_dart_companies(corp_code, corp_name, stock_code, modified_at)
                VALUES (?, ?, ?, ?)
                """, entries, 1000, (statement, entry) -> {
            statement.setString(1, entry.company().corpCode());
            statement.setString(2, entry.company().corpName());
            statement.setString(3, entry.company().stockCode());
            statement.setDate(4, Date.valueOf(entry.modifiedAt()));
        });
    }
}
