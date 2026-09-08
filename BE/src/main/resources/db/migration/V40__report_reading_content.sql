ALTER TABLE news_collection_run_items ADD (
    topic_snapshot CLOB,
    CONSTRAINT ck_run_item_topic_snapshot CHECK (topic_snapshot IS JSON)
);

ALTER TABLE news_reports ADD (
    structured_content CLOB,
    collection_contexts CLOB DEFAULT '[]' NOT NULL,
    CONSTRAINT ck_report_structured_content CHECK (structured_content IS JSON),
    CONSTRAINT ck_report_collection_contexts CHECK (collection_contexts IS JSON)
);
