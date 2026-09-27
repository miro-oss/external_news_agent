-- Keep global weekly reports and manual topic reports in separate durable identities.
ALTER TABLE news_reports ADD (topic_id NUMBER(19), topic_name VARCHAR2(255 CHAR));
ALTER TABLE news_reports ADD CONSTRAINT ck_report_topic_scope CHECK (
    (topic_id IS NULL AND topic_name IS NULL)
    OR (report_scope = 'WEEKLY' AND topic_id IS NOT NULL AND topic_id > 0 AND topic_name IS NOT NULL)
);
-- No FK: deleting a topic must preserve its historical report identity and name snapshot.
DROP INDEX uq_report_weekly_date;
CREATE UNIQUE INDEX uq_report_weekly_date ON news_reports (
    CASE WHEN report_scope = 'WEEKLY' THEN report_date END,
    CASE WHEN report_scope = 'WEEKLY' THEN NVL(topic_id, 0) END
);
