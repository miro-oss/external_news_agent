-- Internal scheduling state: no provider judgment was made. Existing uncertain judgments remain unchanged.
ALTER TABLE news_topic_relevance DROP CONSTRAINT ck_topic_relevance_status;
ALTER TABLE news_topic_relevance ADD CONSTRAINT ck_topic_relevance_status
    CHECK (status IN ('RELEVANT', 'IRRELEVANT', 'UNCERTAIN', 'DEFERRED'));
