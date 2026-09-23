CREATE TABLE recipient_report_inclusions (
    recipient_id NUMBER NOT NULL REFERENCES notification_recipients(id),
    topic_id NUMBER NOT NULL REFERENCES news_topics(id),
    report_scope VARCHAR2(10) NOT NULL CHECK (report_scope IN ('RUN','DAILY','WEEKLY')),
    CONSTRAINT pk_recipient_report_inclusion PRIMARY KEY (recipient_id,topic_id,report_scope)
);

-- Individual opt-in origins are revalidated independently from shared policy
-- and captured run origins. Existing rows retain their original consent rules.
ALTER TABLE report_notification_outbox ADD (
    personal_topic_ids CLOB CHECK (personal_topic_ids IS JSON)
);
