package com.example.be.domain.dart;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"news.agent.enabled=false", "news.scheduling.enabled=false", "news.dart.enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class DartCompanyOracleIntegrationTests {
    @Autowired DartCompanyRepository repository;
    @Autowired JdbcTemplate jdbc;
    private List<DartCompanyArchive.Entry> previous;

    @BeforeEach void preserveExistingTestCorpus() {
        previous = jdbc.query("SELECT corp_code, corp_name, stock_code, modified_at FROM news_dart_companies",
                (rs, row) -> new DartCompanyArchive.Entry(new DartModels.Company(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? "" : rs.getString(3)), rs.getDate(4).toLocalDate()));
    }

    @AfterEach void restoreExistingTestCorpus() {
        if (previous == null) return;
        if (previous.isEmpty()) jdbc.update("DELETE FROM news_dart_companies");
        else repository.replace(previous);
    }

    @Test void searchesWithLiteralWildcardsStableOrderingAndOraclePagination() {
        repository.replace(List.of(entry("99999003", "Test Z", ""), entry("99999002", "Test A", "000002"),
                entry("99999001", "Test", "000001"), entry("99999004", "Test%_Literal", "000004")));
        assertTrue(repository.hasCompanies());
        assertEquals(4L, repository.count("test"));
        var ordered = repository.search("test", 0, 10);
        assertEquals("99999001", ordered.getFirst().corpCode(), "exact name ranks first");
        assertEquals("99999003", ordered.getLast().corpCode(), "unlisted company ranks after listed matches");
        assertEquals(ordered.subList(0, 2), repository.search("test", 0, 2));
        assertEquals(ordered.subList(2, 4), repository.search("test", 1, 2));
        assertTrue(repository.search("test", 2, 2).isEmpty());
        assertEquals(1L, repository.count("%_"));
        assertEquals("99999004", repository.search("%_", 0, 20).getFirst().corpCode());
    }

    @Test void preservesLeadingZerosUnlistedEmptyCodeAndWideKoreanNames() {
        String wideName = "가".repeat(400);
        repository.replace(List.of(entry("99999001", "Listed", "000001"), entry("99999002", wideName, "")));
        assertEquals("000001", repository.find("99999001").orElseThrow().stockCode());
        assertEquals("99999001", repository.search("000001", 0, 20).getFirst().corpCode());
        var unlisted = repository.find("99999002").orElseThrow();
        assertEquals("", unlisted.stockCode());
        assertEquals(wideName, unlisted.corpName());
    }

    @Test void failedReplacementRollsBackDeleteAndAllPartialBatchInserts() {
        var original = entry("99999001", "Original", "000001");
        repository.replace(List.of(original));
        var duplicate = entry("99999002", "Replacement", "000002");
        assertThrows(DataIntegrityViolationException.class, () -> repository.replace(List.of(duplicate, duplicate)));
        assertEquals(original.company(), repository.find("99999001").orElseThrow());
        assertTrue(repository.find("99999002").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM news_dart_companies", Integer.class));
    }

    @Test void storesAndSearchesPublishedAlphanumericStockCodesAfterMigration() {
        repository.replace(List.of(entry("01933981", "비엔케이제3호스팩", "0068Y0"),
                entry("01817920", "페스카로", "0015S0")));
        assertEquals("0068Y0", repository.find("01933981").orElseThrow().stockCode());
        assertEquals("0015S0", repository.find("01817920").orElseThrow().stockCode());
        assertEquals(1L, repository.count("0068Y0"));
        assertEquals("01933981", repository.search("0068Y0", 0, 20).getFirst().corpCode());
    }

    @Test void upgradedStockCodeConstraintRejectsMalformedValuesWithoutReplacingCatalog() {
        var original = entry("01933981", "비엔케이제3호스팩", "0068Y0");
        repository.replace(List.of(original));
        for (String stock : List.of("0068y0", "0068-0", "12345", "1234567", "00가000")) {
            var failure = assertThrows(DataAccessException.class,
                    () -> repository.replace(List.of(entry("01817920", "Invalid", stock))));
            // Oracle batch failures can have an uncategorized Spring wrapper; assert the actual database rejection.
            int expectedOracleCode = stock.length() > 6 ? 12899 : 2290;
            assertTrue(hasOracleErrorCode(failure, expectedOracleCode), "Expected Oracle constraint/length violation for " + stock);
            assertEquals(original.company(), repository.find("01933981").orElseThrow());
            assertTrue(repository.find("01817920").isEmpty());
        }
    }

    private boolean hasOracleErrorCode(Throwable failure, int expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException current = sql; current != null; current = current.getNextException()) {
                    if (current.getErrorCode() == expected) return true;
                }
            }
        }
        return false;
    }

    private DartCompanyArchive.Entry entry(String code, String name, String stock) {
        return new DartCompanyArchive.Entry(new DartModels.Company(code, name, stock), LocalDate.of(2026, 10, 14));
    }
}
