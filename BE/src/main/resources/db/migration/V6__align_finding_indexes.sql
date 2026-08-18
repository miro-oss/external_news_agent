-- 최신 finding 조회는 MAX(id)와 ORDER BY id DESC를 사용하므로 인덱스도 같은 기준으로 맞춘다.
DROP INDEX ix_finding_article_latest;
CREATE INDEX ix_finding_article_latest ON news_findings (article_id, id DESC);

-- uq_finding_run_article의 선두 컬럼이 run_id라 단독 인덱스는 중복이다.
DROP INDEX ix_finding_run;
