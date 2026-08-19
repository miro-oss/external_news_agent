-- 이미 적용된 V8의 체크섬을 보존하면서 Agent 응답 계약을 DB에서도 방어한다.
ALTER TABLE agent_runs MODIFY (failure_message VARCHAR2(1000 CHAR));

ALTER TABLE news_findings ADD CONSTRAINT ck_finding_category
    CHECK (category IN ('제품/공정', '기업', '정책', '공급망'));
