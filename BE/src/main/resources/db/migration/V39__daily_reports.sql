-- RUN은 기존 run당 1개 제약을 유지한다. DAILY는 한국 시간 집계일당 1개다.
ALTER TABLE news_reports MODIFY (run_id NULL);
ALTER TABLE news_reports ADD (
    report_scope VARCHAR2(10) DEFAULT 'RUN' NOT NULL,
    report_date DATE,
    source_run_ids CLOB DEFAULT '[]' NOT NULL
);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_scope CHECK (
    (report_scope = 'RUN' AND run_id IS NOT NULL AND report_date IS NULL)
    OR (report_scope = 'DAILY' AND run_id IS NULL AND report_date IS NOT NULL
        AND report_date = TRUNC(report_date))
);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_source_runs CHECK (source_run_ids IS JSON);
CREATE UNIQUE INDEX uq_report_daily_date ON news_reports (
    CASE WHEN report_scope = 'DAILY' THEN report_date END
);
