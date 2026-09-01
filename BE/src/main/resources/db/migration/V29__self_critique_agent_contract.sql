ALTER TABLE agent_runs DROP CONSTRAINT ck_agent_task;
ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_task
    CHECK (agent_task IN ('ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE'));

ALTER TABLE agent_runs DROP CONSTRAINT ck_agent_target_type;
ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_target_type
    CHECK (target_type IN ('ARTICLE', 'ISSUE', 'FINDING', 'EVENT', 'REPORT', 'RUN'));

ALTER TABLE agent_quota_reservations DROP CONSTRAINT ck_agent_quota_task;
ALTER TABLE agent_quota_reservations ADD CONSTRAINT ck_agent_quota_task
    CHECK (agent_task IN ('ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE'));
