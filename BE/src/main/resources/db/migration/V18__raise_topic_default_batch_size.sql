-- 주제 기본 수집 건수를 10에서 20으로 올린다(#82).
-- batchSize는 SEARCH 소스 한 곳당 요청 건수다. 10이면 dedup과 키워드 필터를 거쳐 3~5건만 남아
-- 보고서 근거로 얇았다. 커넥터 중 천장이 가장 낮은 Tavily(max_results 20)에 맞춰 20으로 둔다.
ALTER TABLE news_topics MODIFY (batch_size DEFAULT 20);

-- 컬럼 DEFAULT는 앞으로 만들 행에만 붙는다. 화면에 batch_size를 고칠 입력이 없어서(#76에서 폼에서
-- 제거) 기존 주제는 그대로 두면 영원히 10에 묶인다. 옛 기본값 그대로인 행만 올리고, 값을 따로
-- 지정해 둔 주제는 그 의도를 덮지 않는다.
UPDATE news_topics SET batch_size = 20 WHERE batch_size = 10;
