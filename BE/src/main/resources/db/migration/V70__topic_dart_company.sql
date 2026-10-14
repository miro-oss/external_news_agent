ALTER TABLE news_topics ADD (
    dart_corp_code VARCHAR2(8 CHAR),
    dart_corp_name VARCHAR2(512 CHAR),
    dart_stock_code VARCHAR2(6 CHAR)
);

ALTER TABLE news_topics ADD CONSTRAINT ck_topic_dart_company CHECK (
    (dart_corp_code IS NULL AND dart_corp_name IS NULL AND dart_stock_code IS NULL)
    OR (dart_corp_code IS NOT NULL AND dart_corp_name IS NOT NULL)
);
