-- 이미 배포된 V33의 체크섬을 유지하면서 조사 사유 컬럼은 문자 수 기준으로 확장한다.
ALTER TABLE news_issue_investigations MODIFY (
    trigger_reason VARCHAR2(1000 CHAR),
    first_action_reason VARCHAR2(1000 CHAR),
    rejection_reason VARCHAR2(1000 CHAR)
);

ALTER TABLE agent_runs MODIFY (
    action_reason VARCHAR2(1000 CHAR),
    rejection_reason VARCHAR2(1000 CHAR)
);
