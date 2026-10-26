CREATE TABLE news_topic_relevance (
    run_id NUMBER(19) NOT NULL,
    topic_id NUMBER(19) NOT NULL,
    article_id NUMBER(19) NOT NULL,
    status VARCHAR2(20) NOT NULL,
    reason VARCHAR2(500 CHAR) NOT NULL,
    input_hash VARCHAR2(64) NOT NULL,
    prompt_version VARCHAR2(50) NOT NULL,
    model_name VARCHAR2(100),
    evidence_quotes CLOB,
    assessed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_news_topic_relevance PRIMARY KEY (run_id, topic_id, article_id),
    CONSTRAINT fk_relevance_run FOREIGN KEY (run_id) REFERENCES news_collection_runs(id),
    CONSTRAINT fk_relevance_topic FOREIGN KEY (topic_id) REFERENCES news_topics(id),
    CONSTRAINT fk_relevance_article FOREIGN KEY (article_id) REFERENCES news_articles(id),
    CONSTRAINT ck_topic_relevance_status CHECK (status IN ('RELEVANT', 'IRRELEVANT', 'UNCERTAIN'))
);
CREATE INDEX ix_relevance_run_article ON news_topic_relevance(run_id, article_id, status);
CREATE INDEX ix_relevance_article_topic ON news_topic_relevance(article_id, topic_id, run_id);

ALTER TABLE agent_runs DROP CONSTRAINT ck_agent_task;
ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_task
    CHECK (agent_task IN ('ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE',
        'INSIGHT', 'INVESTIGATE', 'KEYWORD_STRATEGY', 'REPORT_CHANGES', 'TOPIC_RELEVANCE'));
ALTER TABLE agent_quota_reservations DROP CONSTRAINT ck_agent_quota_task;
ALTER TABLE agent_quota_reservations ADD CONSTRAINT ck_agent_quota_task
    CHECK (agent_task IN ('ANALYZE', 'SELF_CRITIQUE', 'VERIFY_EVIDENCE', 'REPORT', 'EXPLORE',
        'INSIGHT', 'INVESTIGATE', 'KEYWORD_STRATEGY', 'REPORT_CHANGES', 'TOPIC_RELEVANCE'));
