-- AI 모델·Agent·알고리즘의 기술 변화를 다루는 공개 RSS/Atom 소스 5개를 추가한다.
-- 2026-11-09: 각 피드의 XML·발행일과 원문 2개씩의 본문 추출을 현재 수집기로 확인했다.
-- Simon Willison은 전체 글 대신 LLM 태그 피드로 범위를 좁힌다.
-- 공식/연구 블로그에도 기업·일반 연구 소식이 있으므로 주제의 관련성 기준으로 선별한다.
--
-- 카탈로그에만 등록하며 기존 주제에 자동 연결하거나 기존 소스의 운영 설정을 바꾸지 않는다.
-- robots.txt는 실행 시 respect 정책으로 확인한다. 신뢰도 점수는 임의로 부여하지 않는다.
-- maxArticlesPerRun은 실제 선별 상한으로 쓰이지 않는 호환 필드이므로 지정하지 않는다.

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
    SELECT CAST('FEED' AS VARCHAR2(10)) AS source_kind,
           CAST('OpenAI News' AS VARCHAR2(200)) AS name,
           CAST('https://openai.com/news/rss.xml' AS VARCHAR2(1000)) AS url_template,
           CAST('US' AS VARCHAR2(2)) AS country,
           CAST('en' AS VARCHAR2(5)) AS language,
           CAST('{"robotsMode":"respect","fullTextAllowed":true}' AS VARCHAR2(4000)) AS crawl_policy
    FROM dual
    UNION ALL SELECT 'FEED', 'Hugging Face Blog', 'https://huggingface.co/blog/feed.xml', 'US', 'en',
                     '{"robotsMode":"respect","fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Google Research Blog', 'https://research.google/blog/rss/', 'US', 'en',
                     '{"robotsMode":"respect","fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Microsoft Research Blog', 'https://www.microsoft.com/en-us/research/feed/', 'US', 'en',
                     '{"robotsMode":"respect","fullTextAllowed":true}' FROM dual
    UNION ALL SELECT 'FEED', 'Simon Willison LLMs', 'https://simonwillison.net/tags/llms.atom', 'US', 'en',
                     '{"robotsMode":"respect","fullTextAllowed":true}' FROM dual
) seed
WHERE NOT EXISTS (
    SELECT 1
    FROM news_sources existing
    WHERE existing.source_kind = seed.source_kind
      AND existing.url_template = seed.url_template
);
