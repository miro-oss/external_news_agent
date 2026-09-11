ALTER TABLE news_reports ADD (
    comparison_input_usable_yn CHAR(1) DEFAULT 'Y' NOT NULL,
    CONSTRAINT ck_report_cmp_input_usable CHECK (comparison_input_usable_yn IN ('Y', 'N'))
);

-- Input is captured before report generation; legacy reports intentionally have no historical backfill.
CREATE TABLE news_report_comparison_inputs (
    report_id NUMBER(19) PRIMARY KEY REFERENCES news_reports(id),
    snapshot_json CLOB NOT NULL,
    input_hash VARCHAR2(64 CHAR) NOT NULL,
    captured_at TIMESTAMP NOT NULL,
    usable_yn CHAR(1) DEFAULT 'Y' NOT NULL,
    CONSTRAINT ck_report_cmp_input_json CHECK (snapshot_json IS JSON),
    CONSTRAINT ck_report_cmp_usable CHECK (usable_yn IN ('Y', 'N'))
);

CREATE TABLE news_report_comparisons (
    report_id NUMBER(19) PRIMARY KEY REFERENCES news_reports(id),
    base_report_id NUMBER(19) REFERENCES news_reports(id),
    status VARCHAR2(20 CHAR) NOT NULL,
    work_json CLOB,
    result_json CLOB NOT NULL,
    input_hash VARCHAR2(64 CHAR),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT ck_report_cmp_work_json CHECK (work_json IS JSON),
    CONSTRAINT ck_report_cmp_result_json CHECK (result_json IS JSON),
    CONSTRAINT ck_report_cmp_status CHECK (status IN
        ('PENDING','RUNNING','READY','FAILED','NO_BASELINE','UNAVAILABLE','NOT_APPLICABLE'))
);
CREATE INDEX ix_report_cmp_queue ON news_report_comparisons(status, created_at);
CREATE INDEX ix_report_cmp_baseline ON news_reports(report_scope, report_date, deleted_at, report_status);
