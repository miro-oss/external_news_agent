-- A row records an explicit run override; absence retains the legacy topic policy behavior.
CREATE TABLE run_delivery_settings (
    run_id NUMBER PRIMARY KEY REFERENCES news_collection_runs(id),
    delivery_mode VARCHAR2(10) NOT NULL CHECK (delivery_mode IN ('ONCE','TOPIC')),
    enabled_yn CHAR(1) NOT NULL CHECK (enabled_yn IN ('Y','N')),
    run_yn CHAR(1) NOT NULL CHECK (run_yn IN ('Y','N')),
    daily_yn CHAR(1) NOT NULL CHECK (daily_yn IN ('Y','N'))
);

-- Groups are expanded at collection request time; later edits cannot add another recipient.
CREATE TABLE run_delivery_targets (
    run_id NUMBER NOT NULL REFERENCES run_delivery_settings(run_id),
    recipient_id NUMBER NOT NULL REFERENCES notification_recipients(id),
    channel_id NUMBER NOT NULL REFERENCES notification_channels(id),
    recipient_name VARCHAR2(100) NOT NULL,
    address VARCHAR2(500) NOT NULL,
    CONSTRAINT pk_run_delivery_target PRIMARY KEY (run_id,recipient_id,channel_id)
);
