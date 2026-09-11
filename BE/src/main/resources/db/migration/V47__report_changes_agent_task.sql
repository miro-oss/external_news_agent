ALTER TABLE agent_runs DROP CONSTRAINT ck_agent_task;
ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_task
    CHECK (agent_task IN (
        'ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE',
        'INSIGHT', 'INVESTIGATE', 'KEYWORD_STRATEGY', 'REPORT_CHANGES'
    )) ENABLE NOVALIDATE;
ALTER TABLE agent_runs ENABLE VALIDATE CONSTRAINT ck_agent_task;

ALTER TABLE agent_quota_reservations DROP CONSTRAINT ck_agent_quota_task;
ALTER TABLE agent_quota_reservations ADD CONSTRAINT ck_agent_quota_task
    CHECK (agent_task IN (
        'ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE',
        'INSIGHT', 'INVESTIGATE', 'KEYWORD_STRATEGY', 'REPORT_CHANGES'
    )) ENABLE NOVALIDATE;
ALTER TABLE agent_quota_reservations ENABLE VALIDATE CONSTRAINT ck_agent_quota_task;
