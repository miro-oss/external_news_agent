-- Preserve the company selected at new topic weekly report reservation, independently of collection history.
-- Existing reports remain null: never infer their company from current topics or historical input runs.
ALTER TABLE news_reports ADD (
    topic_dart_corp_code VARCHAR2(8 CHAR),
    topic_dart_corp_name VARCHAR2(512 CHAR),
    topic_dart_stock_code VARCHAR2(6 CHAR)
);

ALTER TABLE news_reports ADD CONSTRAINT ck_report_topic_dart_company CHECK (
    (topic_dart_corp_code IS NULL AND topic_dart_corp_name IS NULL AND topic_dart_stock_code IS NULL)
    OR (report_scope = 'WEEKLY' AND topic_id IS NOT NULL
        AND topic_dart_corp_code IS NOT NULL AND topic_dart_corp_name IS NOT NULL
        AND REGEXP_LIKE(topic_dart_corp_code, '^[0-9]{8}$'))
);
