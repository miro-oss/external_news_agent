-- P1-2 리뷰 후속: 실제 분석 대상 수와 보고서 finding 반영 스냅샷을 보존한다.
ALTER TABLE news_collection_runs ADD analysis_target_issue_count NUMBER;
ALTER TABLE news_collection_runs ADD CONSTRAINT ck_run_analysis_target_count
    CHECK (analysis_target_issue_count IS NULL OR analysis_target_issue_count >= 0);

ALTER TABLE news_reports ADD (
    report_reflected_finding_ids CLOB DEFAULT '[]' NOT NULL,
    report_excluded_finding_ids  CLOB DEFAULT '[]' NOT NULL,
    coverage_recorded_yn         CHAR(1) DEFAULT 'N' NOT NULL
);

ALTER TABLE news_reports ADD CONSTRAINT ck_report_reflected_ids_json
    CHECK (report_reflected_finding_ids IS JSON);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_excluded_ids_json
    CHECK (report_excluded_finding_ids IS JSON);
ALTER TABLE news_reports ADD CONSTRAINT ck_report_coverage_recorded
    CHECK (coverage_recorded_yn IN ('Y', 'N'));
