ALTER TABLE news_reports DROP CONSTRAINT ck_news_report_status;

UPDATE news_reports
SET report_status = 'FALLBACK'
WHERE model_name IN ('stub-report-v1', 'safe-fallback-report-v1');

ALTER TABLE news_reports MODIFY (report_status DEFAULT 'FALLBACK');

ALTER TABLE news_reports ADD CONSTRAINT ck_news_report_status
    CHECK (report_status IN ('GENERATED', 'FALLBACK', 'MOCK', 'PENDING'));
