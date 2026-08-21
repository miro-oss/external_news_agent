-- A5. 동일한 제목·요약·본문으로 생성된 실제 LLM finding만 안전하게 재사용한다.
-- 기존 행은 당시 분석 입력을 복원할 수 없어 null로 두고 이후 생성되는 finding부터 캐시 후보가 된다.
ALTER TABLE news_findings ADD analysis_input_hash VARCHAR2(64);

CREATE INDEX ix_finding_reuse_lookup
    ON news_findings (article_id, analysis_source, analysis_input_hash, id DESC);
