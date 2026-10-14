-- Attachments retain the selected company and fetched financial data independently of live topic settings.
CREATE TABLE news_report_dart_attachments (
    report_id NUMBER(19) NOT NULL REFERENCES news_reports(id) ON DELETE CASCADE,
    corp_code VARCHAR2(8) NOT NULL,
    corp_name VARCHAR2(512 CHAR) NOT NULL,
    stock_code VARCHAR2(6),
    status VARCHAR2(10) DEFAULT 'PENDING' NOT NULL,
    message VARCHAR2(500 CHAR) NOT NULL,
    payload_json CLOB,
    attempts NUMBER(3) DEFAULT 0 NOT NULL,
    next_attempt_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    lease_token VARCHAR2(36),
    lease_expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_report_dart_attachment PRIMARY KEY (report_id, corp_code),
    CONSTRAINT ck_report_dart_status CHECK (status IN
        ('PENDING', 'RUNNING', 'READY', 'PARTIAL', 'FAILED', 'DISABLED')),
    CONSTRAINT ck_report_dart_payload CHECK (payload_json IS JSON),
    CONSTRAINT ck_report_dart_attempts CHECK (attempts BETWEEN 0 AND 100)
);
CREATE INDEX ix_report_dart_pending ON news_report_dart_attachments(status, next_attempt_at);

-- The lease coalesces concurrent requests for the same company across reports and application instances.
CREATE TABLE news_dart_financial_cache (
    corp_code VARCHAR2(8) PRIMARY KEY,
    payload_json CLOB,
    cached_at TIMESTAMP,
    lease_token VARCHAR2(36),
    lease_expires_at TIMESTAMP,
    CONSTRAINT ck_dart_cache_payload CHECK (payload_json IS JSON)
);

-- Authentication and quota failures pause the integration, not just one company's job.
CREATE TABLE news_dart_fetch_state (
    id NUMBER(1) PRIMARY KEY,
    paused_until TIMESTAMP,
    CONSTRAINT ck_dart_fetch_singleton CHECK (id = 1)
);
INSERT INTO news_dart_fetch_state (id) VALUES (1);
