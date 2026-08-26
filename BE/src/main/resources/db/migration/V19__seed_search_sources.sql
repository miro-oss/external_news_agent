-- 검색 커넥터가 지원하는 provider를 설정 화면에서 바로 선택할 수 있도록 기본 소스로 등록한다.
--
-- 실제 API 키는 환경변수에만 두며 DB에는 논리 provider 키만 저장한다.
-- 소스 등록만 하고 주제와 자동 연결하지 않으므로, 마이그레이션 적용만으로 외부 API가 호출되지는 않는다.
-- 이미 같은 (source_kind, url_template)이 있으면 기존 이름/설정을 보존하고 건너뛴다.

INSERT INTO news_sources (source_kind, name, url_template, country, language, robots_status, active_yn)
SELECT seed.source_kind,
       seed.name,
       seed.url_template,
       seed.country,
       seed.language,
       'unknown',
       'Y'
FROM (
    SELECT CAST('SEARCH' AS VARCHAR2(10))            AS source_kind,
           CAST('Naver 뉴스 검색' AS VARCHAR2(200))   AS name,
           CAST('NAVER' AS VARCHAR2(1000))           AS url_template,
           CAST('KR' AS VARCHAR2(2))                 AS country,
           CAST('ko' AS VARCHAR2(5))                 AS language
    FROM dual
    UNION ALL SELECT 'SEARCH', 'Tavily 뉴스 검색', 'TAVILY', NULL, 'en' FROM dual
    UNION ALL SELECT 'SEARCH', 'SerpAPI Google 뉴스 검색', 'SERPAPI', NULL, 'ko' FROM dual
) seed
WHERE NOT EXISTS (
    SELECT 1
    FROM news_sources existing
    WHERE existing.source_kind = seed.source_kind
      AND existing.url_template = seed.url_template
);
