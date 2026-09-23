-- Weekly inputs are immutable copies of the completed daily reports used at reservation time.
ALTER TABLE news_reports ADD (
    report_end_date DATE,
    source_report_ids CLOB DEFAULT '[]' NOT NULL,
    source_report_dates CLOB DEFAULT '[]' NOT NULL,
    missing_report_dates CLOB DEFAULT '[]' NOT NULL,
    weekly_input CLOB
);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_source_reports CHECK (source_report_ids IS JSON);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_source_dates CHECK (source_report_dates IS JSON);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_missing_dates CHECK (missing_report_dates IS JSON);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_weekly_input CHECK (weekly_input IS JSON);
ALTER TABLE news_reports DROP CONSTRAINT ck_report_scope;
ALTER TABLE news_reports ADD CONSTRAINT ck_report_scope CHECK (
    (report_scope = 'RUN' AND run_id IS NOT NULL AND report_date IS NULL AND report_end_date IS NULL)
    OR (report_scope = 'DAILY' AND run_id IS NULL AND report_date IS NOT NULL
        AND report_date = TRUNC(report_date) AND report_end_date IS NULL)
    OR (report_scope = 'WEEKLY' AND run_id IS NULL AND report_date IS NOT NULL
        AND report_date = TRUNC(report_date, 'IW') AND report_end_date IS NOT NULL
        AND report_end_date = report_date + 6 AND weekly_input IS NOT NULL)
);
CREATE UNIQUE INDEX uq_report_weekly_date ON news_reports (
    CASE WHEN report_scope = 'WEEKLY' THEN report_date END
);
