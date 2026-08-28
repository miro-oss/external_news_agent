-- 무료 운영 기준으로 NAVER만 기본 활성 검색 provider로 둔다(#96).
-- 커넥터와 provider enum은 삭제하지 않아, 필요하면 운영자가 소스를 다시 활성화할 수 있다.
UPDATE news_sources
SET active_yn = 'N'
WHERE source_kind = 'SEARCH'
  AND url_template IN ('TAVILY', 'SERPAPI')
  AND active_yn = 'Y';
