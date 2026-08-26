package com.example.be;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V19__seed_search_sources.sql이 세 검색 provider를 멱등하게 등록하는지 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SeedSearchSourcesMigrationIntegrationTests {

    private static final String SEED_SCRIPT = "db/migration/V19__seed_search_sources.sql";

    private static final List<SeededSearchSource> SEEDED_SOURCES = List.of(
            new SeededSearchSource("NAVER", "Naver 뉴스 검색", "KR", "ko"),
            new SeededSearchSource("TAVILY", "Tavily 뉴스 검색", null, "en"),
            new SeededSearchSource("SERPAPI", "SerpAPI Google 뉴스 검색", null, "ko")
    );

    private static final Set<String> SEEDED_PROVIDER_KEYS = SEEDED_SOURCES.stream()
            .map(SeededSearchSource::providerKey)
            .collect(Collectors.toUnmodifiableSet());

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesSeedMigration() {
        List<String> versions = jdbcTemplate.queryForList("""
                SELECT "version"
                FROM "flyway_schema_history"
                WHERE "success" = 1
                ORDER BY "installed_rank"
                """, String.class);

        assertTrue(versions.contains("19"));
    }

    @Test
    void seedsEverySearchProvider() {
        Map<String, Source> seeded = seededSources();

        assertEquals(SEEDED_SOURCES.size(), seeded.size());
        SEEDED_SOURCES.forEach(expected -> {
            Source source = seeded.get(expected.providerKey());
            assertNotNull(source, expected.providerKey() + "이(가) 시드되지 않았다");
            assertEquals(expected.name(), source.getName(), expected.providerKey());
            assertEquals(expected.country(), source.getCountry(), expected.providerKey());
            assertEquals(expected.language(), source.getLanguage(), expected.providerKey());
        });
    }

    @Test
    void seedsProvidersAsActiveSearchSourcesWithoutSecrets() {
        seededSources().forEach((providerKey, source) -> {
            assertEquals(Source.KIND_SEARCH, source.getSourceKind(), providerKey);
            assertEquals(Source.ROBOTS_STATUS_UNKNOWN, source.getRobotsStatus(), providerKey);
            assertTrue(source.isActive(), providerKey);

            assertNull(source.getCrawlPolicy(), providerKey);
            assertNull(source.getReliabilityScore(), providerKey);
        });
    }

    @Test
    void insertsNothingWhenSeedScriptRunsAgain() throws Exception {
        long before = sourceRepository.count();

        assertEquals(0, jdbcTemplate.update(readSeedScript()));
        assertEquals(before, sourceRepository.count());
    }

    private Map<String, Source> seededSources() {
        return sourceRepository.findAll().stream()
                .filter(source -> Source.KIND_SEARCH.equals(source.getSourceKind()))
                .filter(source -> SEEDED_PROVIDER_KEYS.contains(source.getUrlTemplate()))
                .collect(Collectors.toMap(Source::getUrlTemplate, Function.identity()));
    }

    private String readSeedScript() throws Exception {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(SEED_SCRIPT).getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader).strip().replaceAll(";$", "");
        }
    }

    private record SeededSearchSource(String providerKey, String name, String country, String language) {
    }
}
