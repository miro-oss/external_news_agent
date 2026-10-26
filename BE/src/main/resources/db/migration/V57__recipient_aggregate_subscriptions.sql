-- Absent rows preserve existing topic/run consent until the recipient chooses.
CREATE TABLE recipient_aggregate_subscriptions (
    recipient_id NUMBER NOT NULL REFERENCES notification_recipients(id),
    report_scope VARCHAR2(10) NOT NULL CHECK (report_scope IN ('DAILY','WEEKLY')),
    enabled_yn CHAR(1) NOT NULL CHECK (enabled_yn IN ('Y','N')),
    CONSTRAINT pk_recipient_aggregate_sub PRIMARY KEY (recipient_id,report_scope)
);

ALTER TABLE report_notification_outbox ADD (
    aggregate_yn CHAR(1) DEFAULT 'N' NOT NULL CHECK (aggregate_yn IN ('Y','N'))
);
