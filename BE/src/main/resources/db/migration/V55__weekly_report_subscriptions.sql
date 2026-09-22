ALTER TABLE topic_delivery_policies ADD (
    weekly_yn CHAR(1) DEFAULT 'N' NOT NULL CHECK (weekly_yn IN ('Y','N'))
);

CREATE TABLE recipient_report_exclusions (
    recipient_id NUMBER NOT NULL REFERENCES notification_recipients(id),
    topic_id NUMBER NOT NULL REFERENCES news_topics(id),
    report_scope VARCHAR2(10) NOT NULL CHECK (report_scope IN ('RUN','DAILY','WEEKLY')),
    CONSTRAINT pk_recipient_report_exclusion PRIMARY KEY (recipient_id,topic_id,report_scope)
);

-- NULL identifies old queued work whose original consent paths were not recorded.
-- New entries capture only topics that actually authorized this recipient/channel.
ALTER TABLE report_notification_outbox ADD (
    source_topic_ids CLOB CHECK (source_topic_ids IS JSON)
);
