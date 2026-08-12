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
import java.util.Set;
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

    /**
     * country/language는 F10(크로스링구얼)이 해외 소스를 골라내는 기준이고 설정 화면의 표시값이기도 하다.
     * 시드가 잘못 넣어도 조회는 그대로 성공하므로 URL만 보지 않고 값까지 고정해 둔다.
     */
    private static final List<SeededSource> SEEDED_SOURCES = List.of(
            // 국내 11건
            new SeededSource("https://www.hankyung.com/feed/economy", "한국경제 경제", "KR", "ko"),
            new SeededSource("https://www.mk.co.kr/rss/50000001/", "매일경제 이코노미", "KR", "ko"),
            new SeededSource("https://www.mk.co.kr/rss/30100041/", "매일경제 증권·금융", "KR", "ko"),
            new SeededSource("https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ko&gl=KR",
                    "구글 뉴스 비즈니스(KR)", "KR", "ko"),
            new SeededSource("https://www.hankyung.com/feed/politics", "한국경제 정치", "KR", "ko"),
            new SeededSource("https://www.hankyung.com/feed/international", "한국경제 국제·외교", "KR", "ko"),
            new SeededSource("https://www.mk.co.kr/rss/30200030/", "매일경제 정치", "KR", "ko"),
            new SeededSource("https://news.google.com/rss/headlines/section/topic/WORLD?hl=ko&gl=KR",
                    "구글 뉴스 세계(KR)", "KR", "ko"),
            new SeededSource("https://rss.etnews.com/Section901.xml", "전자신문 오늘의뉴스", "KR", "ko"),
            new SeededSource("https://www.hankyung.com/feed/it", "한국경제 IT·과학", "KR", "ko"),
            new SeededSource("https://news.google.com/rss/search?q=%EB%B0%98%EB%8F%84%EC%B2%B4&hl=ko&gl=KR",
                    "구글 뉴스 반도체 검색", "KR", "ko"),
            // 해외 6건
            new SeededSource("https://www.eetimes.com/feed/", "EE Times", "US", "en"),
            new SeededSource("https://semiengineering.com/feed/", "Semiconductor Engineering", "US", "en"),
            new SeededSource("https://semiwiki.com/feed/", "SemiWiki", "US", "en"),
            new SeededSource("https://www.digitimes.com/rss/daily.xml", "Digitimes Asia", "TW", "en"),
            new SeededSource("https://www.trendforce.com/news/feed/", "TrendForce", "TW", "en"),
            new SeededSource("https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss25&id=19854910",
                    "CNBC Technology", "US", "en")
    );

    private static final Set<String> SEEDED_URLS = SEEDED_SOURCES.stream()
            .map(SeededSource::url)
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

        assertTrue(versions.contains("3"));
    }

    @Test
    void seedsEveryVerifiedFeedSource() {
        Map<String, Source> seeded = seededSources();

        assertEquals(SEEDED_SOURCES.size(), seeded.size());
        SEEDED_SOURCES.forEach(expected -> {
            Source source = seeded.get(expected.url());
            assertNotNull(source, expected.url() + " 이(가) 시드되지 않았다");
            assertEquals(expected.name(), source.getName(), expected.url());
            assertEquals(expected.country(), source.getCountry(), expected.url());
            assertEquals(expected.language(), source.getLanguage(), expected.url());
        });
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

    private record SeededSource(String url, String name, String country, String language) {
    }
}
