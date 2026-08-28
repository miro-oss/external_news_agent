-- 유료 검색 API 의존도를 낮추기 위해 실제 RSS 응답과 최신 항목을 확인한 무료 피드 8개를 추가한다(#96).
-- robots_status는 F6의 robots 검사기가 판정하도록 unknown으로 시작한다.
-- 이미 같은 (source_kind, url_template)이 있으면 기존 운영 설정을 보존하고 건너뛴다.

INSERT INTO news_sources (source_kind, name, url_template, country, language, crawl_policy, robots_status, active_yn)
SELECT seed.source_kind,
       seed.name,
       seed.url_template,
       seed.country,
       seed.language,
       seed.crawl_policy,
       'unknown',
       'Y'
FROM (
    SELECT CAST('FEED' AS VARCHAR2(10))                                         AS source_kind,
           CAST('SK hynix Newsroom' AS VARCHAR2(200))                          AS name,
           CAST('https://news.skhynix.com/en/feed/' AS VARCHAR2(1000))         AS url_template,
           CAST('KR' AS VARCHAR2(2))                                            AS country,
           CAST('en' AS VARCHAR2(5))                                            AS language,
           CAST('{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' AS VARCHAR2(4000)) AS crawl_policy
    FROM dual
    UNION ALL SELECT 'FEED', 'ZDNet Korea', 'https://feeds.feedburner.com/zdkorea', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Electronics Weekly', 'https://www.electronicsweekly.com/feed/', 'GB', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Semiconductor Digest', 'https://www.semiconductor-digest.com/feed/', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Intel Newsroom', 'https://newsroom.intel.com/feed', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Lam Research IR',
                     'https://investor.lamresearch.com/index.php?s=43&pagetemplate=rss', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'KLA IR', 'https://ir.kla.com/news-events/press-releases/rss', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'AMD IR', 'https://ir.amd.com/rss/news-releases.xml', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
) seed
WHERE NOT EXISTS (
    SELECT 1
    FROM news_sources existing
    WHERE existing.source_kind = seed.source_kind
      AND existing.url_template = seed.url_template
);
