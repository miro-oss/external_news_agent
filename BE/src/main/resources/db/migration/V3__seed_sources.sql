-- 엑셀 `뉴스의사결정시스템지원 1.xlsx`의 RSS / RSS_해외 시트를 수집 소스로 넣는다 (plan-final M2, F10-a).
--
-- ⚠️ 시트 URL을 그대로 넣지 않았다. 22건을 전부 호출해 본 결과 상당수가 RSS 피드가 아니었다.
--    FEED로 등록하면 M3의 RSS 파서가 HTML을 받아 실패하므로, 응답이 피드로 확인된 URL만 넣는다.
--
--    국내 12건 → 11건
--      · 전자신문 www.etnews.com/rss/fed_section_it.xml 이 404라 rss.etnews.com/Section901.xml 로 대체
--      · 지디넷 zdnet.co.kr/rss/news_section_0050.xml 은 404이고 대체 경로도 못 찾아 제외
--
--    해외 10건 → 6건. 시트 URL 중 RSS 피드는 하나도 없었고 전부 HTML 섹션 페이지였다.
--      · EE Times / Semiconductor Engineering / SemiWiki / TrendForce / Digitimes / CNBC 는 피드 경로로 대체
--      · WSTS, TechInsights 는 피드를 찾지 못해 제외
--      · Reuters(401), Nikkei Asia 는 plan-final §4-3이 페이월로 지목한 매체이고 무료 피드가 없어 제외
--
--    제외한 소스는 올바른 URL을 찾으면 POST /api/news/sources 로 등록하면 된다.
--
-- robots_status 는 unknown 으로 시작한다. robots.txt 확인은 F6(M3)이 한다.
-- reliability_score 는 넣지 않는다. 소스 신뢰도는 Tier C `[디벨롭-10]` 보류 항목이라 숫자를 지어내지 않는다.
-- 이미 같은 (source_kind, url_template) 이 있으면 건너뛴다. 손으로 등록해 둔 소스와 충돌하지 않게 한다.

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
    -- 국내: 경제·비즈니스
    SELECT CAST('FEED' AS VARCHAR2(10))                                         AS source_kind,
           CAST('한국경제 경제' AS VARCHAR2(200))                                 AS name,
           CAST('https://www.hankyung.com/feed/economy' AS VARCHAR2(1000))       AS url_template,
           CAST('KR' AS VARCHAR2(2))                                            AS country,
           CAST('ko' AS VARCHAR2(5))                                            AS language,
           CAST('{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' AS VARCHAR2(4000)) AS crawl_policy
    FROM dual
    UNION ALL SELECT 'FEED', '매일경제 이코노미', 'https://www.mk.co.kr/rss/50000001/', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '매일경제 증권·금융', 'https://www.mk.co.kr/rss/30100041/', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '구글 뉴스 비즈니스(KR)',
                     'https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ko&gl=KR', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual

    -- 국내: 정치·국제 정세 (공급망 및 보조금 정책)
    UNION ALL SELECT 'FEED', '한국경제 정치', 'https://www.hankyung.com/feed/politics', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '한국경제 국제·외교', 'https://www.hankyung.com/feed/international', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '매일경제 정치', 'https://www.mk.co.kr/rss/30200030/', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '구글 뉴스 세계(KR)',
                     'https://news.google.com/rss/headlines/section/topic/WORLD?hl=ko&gl=KR', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual

    -- 국내: 반도체·IT 전문
    UNION ALL SELECT 'FEED', '전자신문 오늘의뉴스', 'https://rss.etnews.com/Section901.xml', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '한국경제 IT·과학', 'https://www.hankyung.com/feed/it', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', '구글 뉴스 반도체 검색',
                     'https://news.google.com/rss/search?q=%EB%B0%98%EB%8F%84%EC%B2%B4&hl=ko&gl=KR', 'KR', 'ko',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual

    -- 해외: 글로벌 반도체 전문 매체
    UNION ALL SELECT 'FEED', 'EE Times', 'https://www.eetimes.com/feed/', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Semiconductor Engineering', 'https://semiengineering.com/feed/', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'SemiWiki', 'https://semiwiki.com/feed/', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Digitimes Asia', 'https://www.digitimes.com/rss/daily.xml', 'TW', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual

    -- 해외: 시장 조사 기관 · 종합 비즈니스
    UNION ALL SELECT 'FEED', 'TrendForce', 'https://www.trendforce.com/news/feed/', 'TW', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'CNBC Technology',
                     'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss25&id=19854910', 'US', 'en',
                     '{"robotsMode":"respect","maxArticlesPerRun":30,"fullTextAllowed":true}' FROM dual
) seed
WHERE NOT EXISTS (
    SELECT 1
    FROM news_sources existing
    WHERE existing.source_kind = seed.source_kind
      AND existing.url_template = seed.url_template
);
