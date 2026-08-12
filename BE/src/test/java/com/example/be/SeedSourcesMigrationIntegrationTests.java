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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3__seed_sources.sql이 넣는 RSS/RSS_해외 시드를 검증한다.
 * 시드 URL은 마이그레이션을 작성할 때 전부 실제로 호출해 피드 응답을 확인한 것이고,
 * 여기서는 그 목록이 그대로 들어갔는지와 재실행해도 중복이 생기지 않는지만 본다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SeedSourcesMigrationIntegrationTests {

    private static final String SEED_SCRIPT = "db/migration/V3__seed_sources.sql";

    private static final List<String> SEEDED_URLS = List.of(
            // 국내 11건
            "https://www.hankyung.com/feed/economy",
            "https://www.mk.co.kr/rss/50000001/",
            "https://www.mk.co.kr/rss/30100041/",
            "https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ko&gl=KR",
            "https://www.hankyung.com/feed/politics",
            "https://www.hankyung.com/feed/international",
            "https://www.mk.co.kr/rss/30200030/",
            "https://news.google.com/rss/headlines/section/topic/WORLD?hl=ko&gl=KR",
            "https://rss.etnews.com/Section901.xml",
            "https://www.hankyung.com/feed/it",
            "https://news.google.com/rss/search?q=%EB%B0%98%EB%8F%84%EC%B2%B4&hl=ko&gl=KR",
            // 해외 6건
            "https://www.eetimes.com/feed/",
            "https://semiengineering.com/feed/",
            "https://semiwiki.com/feed/",
            "https://www.digitimes.com/rss/daily.xml",
            "https://www.trendforce.com/news/feed/",
            "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss25&id=19854910"
    );

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

        assertTrue(versions.contains("3"));
    }

    @Test
    void seedsEveryVerifiedFeedSource() {
        Map<String, Source> seeded = seededSources();

        assertEquals(SEEDED_URLS.size(), seeded.size());
        SEEDED_URLS.forEach(url -> assertNotNull(seeded.get(url), url + " 이(가) 시드되지 않았다"));
    }

    /**
     * 시드는 손으로 쓴 JSON을 crawl_policy에 넣는다. CrawlPolicyConverter가 읽지 못하면 소스 조회 API가 통째로 깨진다.
     */
    @Test
    void seedsSourcesAsActiveFeedsWithReadableCrawlPolicy() {
        seededSources().forEach((url, source) -> {
            assertEquals(Source.KIND_FEED, source.getSourceKind(), url);
            assertEquals(Source.ROBOTS_STATUS_UNKNOWN, source.getRobotsStatus(), url);
            assertTrue(source.isActive(), url);

            // 소스 신뢰도는 Tier C 보류 항목이라 시드가 값을 지어내지 않는다.
            assertNull(source.getReliabilityScore(), url);

            CrawlPolicy policy = source.getCrawlPolicy();
            assertNotNull(policy, url);
            assertEquals(CrawlPolicy.ROBOTS_MODE_RESPECT, policy.robotsMode(), url);
            assertEquals(30, policy.maxArticlesPerRun(), url);
            assertTrue(policy.fullTextAllowed(), url);
        });
    }

    /**
     * (source_kind, url_template)에 UNIQUE가 걸려 있어서, 시드가 이미 있는 소스를 거르지 못하면
     * 손으로 등록해 둔 소스가 있는 환경에서 마이그레이션 자체가 실패한다.
     */
    @Test
    void insertsNothingWhenSeedScriptRunsAgain() throws Exception {
        long before = sourceRepository.count();

        assertEquals(0, jdbcTemplate.update(readSeedScript()));
        assertEquals(before, sourceRepository.count());
    }

    private Map<String, Source> seededSources() {
        return sourceRepository.findAll().stream()
                .filter(source -> SEEDED_URLS.contains(source.getUrlTemplate()))
                .collect(Collectors.toMap(Source::getUrlTemplate, Function.identity()));
    }

    /**
     * 마이그레이션은 INSERT 한 문장이다. Oracle JDBC는 끝의 세미콜론을 받지 않으므로 떼고 넘긴다.
     */
    private String readSeedScript() throws Exception {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(SEED_SCRIPT).getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader).strip().replaceAll(";$", "");
        }
    }
}
