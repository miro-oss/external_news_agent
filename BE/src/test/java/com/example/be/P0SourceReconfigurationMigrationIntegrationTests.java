package com.example.be;

import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class P0SourceReconfigurationMigrationIntegrationTests {

    private static final String DISABLE_PAID_SEARCH_SCRIPT =
            "db/migration/V22__disable_paid_search_sources.sql";
    private static final String EXPAND_FEED_SCRIPT =
            "db/migration/V23__expand_feed_sources.sql";

    private static final List<SeededFeed> SEEDED_FEEDS = List.of(
            new SeededFeed("https://news.skhynix.com/en/feed/", "SK hynix Newsroom", "KR", "en"),
            new SeededFeed("https://feeds.feedburner.com/zdkorea", "ZDNet Korea", "KR", "ko"),
            new SeededFeed("https://www.electronicsweekly.com/feed/", "Electronics Weekly", "GB", "en"),
            new SeededFeed("https://www.semiconductor-digest.com/feed/", "Semiconductor Digest", "US", "en"),
            new SeededFeed("https://newsroom.intel.com/feed", "Intel Newsroom", "US", "en"),
            new SeededFeed("https://investor.lamresearch.com/index.php?s=43&pagetemplate=rss",
                    "Lam Research IR", "US", "en"),
            new SeededFeed("https://ir.kla.com/news-events/press-releases/rss", "KLA IR", "US", "en"),
            new SeededFeed("https://ir.amd.com/rss/news-releases.xml", "AMD IR", "US", "en")
    );

    private static final Set<String> SEEDED_URLS = SEEDED_FEEDS.stream()
            .map(SeededFeed::url)
            .collect(Collectors.toUnmodifiableSet());

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesP0Migrations() {
        List<String> versions = jdbcTemplate.queryForList("""
                SELECT "version"
                FROM "flyway_schema_history"
                WHERE "success" = 1
                ORDER BY "installed_rank"
                """, String.class);

        assertTrue(versions.containsAll(List.of("22", "23", "24")));
    }

    @Test
    void keepsOnlyNaverActiveAmongSeededSearchProviders() {
        Map<String, Source> providers = sourceRepository.findAll().stream()
                .filter(source -> Source.KIND_SEARCH.equals(source.getSourceKind()))
                .filter(source -> Set.of("NAVER", "TAVILY", "SERPAPI").contains(source.getUrlTemplate()))
                .collect(Collectors.toMap(Source::getUrlTemplate, Function.identity()));

        assertEquals(3, providers.size());
        assertTrue(providers.get("NAVER").isActive());
        assertFalse(providers.get("TAVILY").isActive());
        assertFalse(providers.get("SERPAPI").isActive());
    }

    @Test
    void seedsVerifiedFeedsWithReadablePolicy() {
        Map<String, Source> feeds = seededFeeds();

        assertEquals(SEEDED_FEEDS.size(), feeds.size());
        SEEDED_FEEDS.forEach(expected -> {
            Source source = feeds.get(expected.url());
            assertNotNull(source, expected.url());
            assertEquals(expected.name(), source.getName(), expected.url());
            assertEquals(expected.country(), source.getCountry(), expected.url());
            assertEquals(expected.language(), source.getLanguage(), expected.url());
            assertTrue(source.isActive(), expected.url());

            CrawlPolicy policy = source.getCrawlPolicy();
            assertNotNull(policy, expected.url());
            assertEquals(CrawlPolicy.ROBOTS_MODE_RESPECT, policy.robotsMode(), expected.url());
            assertEquals(30, policy.maxArticlesPerRun(), expected.url());
            assertTrue(policy.fullTextAllowed(), expected.url());
        });
    }

    @Test
    void sourceMigrationsAreIdempotent() throws Exception {
        long before = sourceRepository.count();

        assertEquals(0, jdbcTemplate.update(readScript(DISABLE_PAID_SEARCH_SCRIPT)));
        assertEquals(0, jdbcTemplate.update(readScript(EXPAND_FEED_SCRIPT)));
        assertEquals(before, sourceRepository.count());
    }

    @Test
    void usesOneHundredAsDatabaseDefaultBatchSize() {
        jdbcTemplate.update("INSERT INTO news_topics (name) VALUES (?)", "P0 기본값 검증");

        Integer batchSize = jdbcTemplate.queryForObject(
                "SELECT batch_size FROM news_topics WHERE name = ?", Integer.class, "P0 기본값 검증");

        assertEquals(100, batchSize);
    }

    @Test
    void acceptsThreeHundredAndRejectsLargerBatchSize() {
        assertEquals(1, jdbcTemplate.update(
                "INSERT INTO news_topics (name, batch_size) VALUES (?, ?)", "P0 최대값 검증", 300));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO news_topics (name, batch_size) VALUES (?, ?)", "P0 초과값 검증", 301));
    }

    private Map<String, Source> seededFeeds() {
        return sourceRepository.findAll().stream()
                .filter(source -> SEEDED_URLS.contains(source.getUrlTemplate()))
                .collect(Collectors.toMap(Source::getUrlTemplate, Function.identity()));
    }

    private String readScript(String path) throws Exception {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader).strip().replaceAll(";$", "");
        }
    }

    private record SeededFeed(String url, String name, String country, String language) {
    }
}
