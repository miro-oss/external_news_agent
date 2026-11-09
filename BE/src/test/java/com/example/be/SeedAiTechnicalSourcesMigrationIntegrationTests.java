package com.example.be;

import com.example.be.domain.sources.entity.CrawlPolicy;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SeedAiTechnicalSourcesMigrationIntegrationTests {

    private static final String SEED_SCRIPT = "db/migration/V82__seed_ai_technical_feeds.sql";
    private static final Map<String, String> SOURCES = Map.of(
            "https://openai.com/news/rss.xml", "OpenAI News",
            "https://huggingface.co/blog/feed.xml", "Hugging Face Blog",
            "https://research.google/blog/rss/", "Google Research Blog",
            "https://www.microsoft.com/en-us/research/feed/", "Microsoft Research Blog",
            "https://simonwillison.net/tags/llms.atom", "Simon Willison LLMs"
    );

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsVerifiedFeedsWithReadableRespectPolicyWithoutAutomaticTopicLinks() {
        Map<String, Source> seeded = sourceRepository.findAll().stream()
                .filter(source -> Source.KIND_FEED.equals(source.getSourceKind()))
                .filter(source -> SOURCES.containsKey(source.getUrlTemplate()))
                .collect(Collectors.toMap(Source::getUrlTemplate, Function.identity()));

        assertEquals(SOURCES.size(), seeded.size());
        SOURCES.forEach((url, name) -> {
            Source source = seeded.get(url);
            assertNotNull(source, url);
            assertEquals(name, source.getName(), url);
            assertEquals("US", source.getCountry(), url);
            assertEquals("en", source.getLanguage(), url);
            assertTrue(source.isActive(), url);
            assertEquals(Source.ROBOTS_STATUS_UNKNOWN, source.getRobotsStatus(), url);
            assertNull(source.getReliabilityScore(), url);

            CrawlPolicy policy = source.getCrawlPolicy();
            assertNotNull(policy, url);
            assertEquals(CrawlPolicy.ROBOTS_MODE_RESPECT, policy.robotsMode(), url);
            assertEquals(Boolean.TRUE, policy.fullTextAllowed(), url);
            assertNull(policy.maxArticlesPerRun(), url);
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM news_topic_sources WHERE source_id = ?",
                    Integer.class, source.getId()), url);
        });
    }

    @Test
    void rerunningSeedPreservesOperatorSettingsAndDoesNotDuplicateSourcesOrTopicLinks() throws Exception {
        String url = "https://simonwillison.net/tags/llms.atom";
        String operatorPolicy = "{\"robotsMode\":\"respect\",\"fullTextAllowed\":false}";
        assertEquals(1, jdbcTemplate.update("""
                UPDATE news_sources
                SET name = ?, active_yn = 'N', crawl_policy = ?, robots_status = 'allowed'
                WHERE source_kind = 'FEED' AND url_template = ?
                """, "Operator-selected LLM feed", operatorPolicy, url));
        long sourcesBefore = sourceRepository.count();
        Long linksBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM news_topic_sources", Long.class);

        assertEquals(0, jdbcTemplate.update(readSeedScript()));

        assertEquals(sourcesBefore, sourceRepository.count());
        assertEquals(linksBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM news_topic_sources", Long.class));
        assertEquals("Operator-selected LLM feed", jdbcTemplate.queryForObject(
                "SELECT name FROM news_sources WHERE source_kind = 'FEED' AND url_template = ?", String.class, url));
        assertEquals("N", jdbcTemplate.queryForObject(
                "SELECT active_yn FROM news_sources WHERE source_kind = 'FEED' AND url_template = ?", String.class, url));
        assertEquals(operatorPolicy, jdbcTemplate.queryForObject(
                "SELECT crawl_policy FROM news_sources WHERE source_kind = 'FEED' AND url_template = ?", String.class, url));
        assertEquals("allowed", jdbcTemplate.queryForObject(
                "SELECT robots_status FROM news_sources WHERE source_kind = 'FEED' AND url_template = ?", String.class, url));
    }

    @Test
    void existingMixedCaseHostKeepsOperatorSettingsWithoutCreatingAnotherSource() throws Exception {
        String canonical = "https://openai.com/news/rss.xml";
        String mixedCase = "HTTPS://OpenAI.COM/news/rss.xml";
        jdbcTemplate.update("""
                UPDATE news_sources SET url_template = ?, name = 'Operator feed', active_yn = 'N'
                WHERE source_kind = 'FEED' AND url_template = ?
                """, mixedCase, canonical);
        long before = sourceRepository.count();

        assertEquals(0, jdbcTemplate.update(readSeedScript()));

        assertEquals(before, sourceRepository.count());
        assertEquals("Operator feed", jdbcTemplate.queryForObject(
                "SELECT name FROM news_sources WHERE url_template = ?", String.class, mixedCase));
        assertEquals("N", jdbcTemplate.queryForObject(
                "SELECT active_yn FROM news_sources WHERE url_template = ?", String.class, mixedCase));
    }

    @Test
    void differentPathCaseOrSourceKindDoesNotSuppressTheFeedSeed() throws Exception {
        jdbcTemplate.update("""
                UPDATE news_sources SET url_template = 'https://OpenAI.COM/NEWS/rss.xml'
                WHERE source_kind = 'FEED' AND url_template = 'https://openai.com/news/rss.xml'
                """);
        jdbcTemplate.update("""
                UPDATE news_sources SET source_kind = 'SEARCH'
                WHERE source_kind = 'FEED' AND url_template = 'https://huggingface.co/blog/feed.xml'
                """);

        assertEquals(2, jdbcTemplate.update(readSeedScript()));
        assertEquals(0, jdbcTemplate.update(readSeedScript()));

        for (String url : SOURCES.keySet()) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM news_sources WHERE source_kind = 'FEED' AND url_template = ?",
                    Integer.class, url));
        }
    }

    private String readSeedScript() throws Exception {
        try (var reader = new InputStreamReader(
                new ClassPathResource(SEED_SCRIPT).getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader).strip().replaceAll(";$", "");
        }
    }
}
