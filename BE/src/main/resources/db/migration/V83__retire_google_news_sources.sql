-- Google News 중계 링크는 현재 원문 수집 경로에서 사용할 수 없어 수집 대상에서 제외한다.
-- 과거 기사·실행·소스 행은 보존하고, 향후 실행에 쓰이는 주제 연결과 활성 상태만 변경한다.
-- research.google 등 다른 Google 기술 블로그에는 적용하지 않는다.

DELETE FROM news_topic_sources
WHERE source_id IN (
    SELECT id FROM news_sources
    WHERE (source_kind = 'FEED'
           AND REGEXP_LIKE(TRIM(url_template), '^https?://news[.]google[.]com(:[0-9]+)?([/?#]|$)', 'i'))
       OR (source_kind = 'SEARCH' AND UPPER(TRIM(url_template)) = 'SERPAPI')
);

UPDATE news_sources
SET active_yn = 'N'
WHERE active_yn = 'Y'
  AND ((source_kind = 'FEED'
        AND REGEXP_LIKE(TRIM(url_template), '^https?://news[.]google[.]com(:[0-9]+)?([/?#]|$)', 'i'))
       OR (source_kind = 'SEARCH' AND UPPER(TRIM(url_template)) = 'SERPAPI'));
