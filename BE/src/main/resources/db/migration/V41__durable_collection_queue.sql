ALTER TABLE news_collection_runs ADD (queued_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL);
UPDATE news_collection_runs SET queued_at = started_at WHERE started_at IS NOT NULL;
ALTER TABLE news_collection_runs MODIFY (started_at NULL);
CREATE INDEX ix_run_queue ON news_collection_runs (status, id);

CREATE TABLE news_collection_dispatch_lock (
    id NUMBER(19) PRIMARY KEY,
    CONSTRAINT ck_collection_dispatch_lock CHECK (id = 1)
);
INSERT INTO news_collection_dispatch_lock (id) VALUES (1);

ALTER TABLE news_topics MODIFY (interval_minutes DEFAULT 1440);
