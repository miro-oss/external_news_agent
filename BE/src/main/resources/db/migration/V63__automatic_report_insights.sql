-- Completion intent shares the report write; registering jobs can safely recover after a restart.
-- Existing reports retain N: deployment must not trigger an unbounded historical LLM backfill.
ALTER TABLE news_reports ADD insight_auto_requested_yn CHAR(1) DEFAULT 'N' NOT NULL;
ALTER TABLE news_reports ADD CONSTRAINT ck_report_insight_auto_yn
    CHECK (insight_auto_requested_yn IN ('Y', 'N'));

CREATE TABLE news_report_insight_jobs (
    report_id NUMBER(19) NOT NULL REFERENCES news_reports(id) ON DELETE CASCADE,
    audience VARCHAR2(30) NOT NULL,
    status VARCHAR2(10) DEFAULT 'PENDING' NOT NULL,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT pk_report_insight_job PRIMARY KEY (report_id, audience),
    CONSTRAINT ck_report_insight_job_aud CHECK (audience IN
        ('CHIP_MAKER', 'EQUIPMENT_MAKER', 'MARKET_INVESTOR', 'IT_INFRA')),
    CONSTRAINT ck_report_insight_job_status CHECK (status IN
        ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX ix_report_insight_job_queue ON news_report_insight_jobs(status, created_at, report_id);
CREATE INDEX ix_report_insight_auto ON news_reports(insight_auto_requested_yn, report_status, deleted_at);
