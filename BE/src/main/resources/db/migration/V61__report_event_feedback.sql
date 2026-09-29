-- Local report opinions are event-scoped reviews, with no invented delivery recipient or finding.
ALTER TABLE news_feedback MODIFY (capability_id NULL, recipient_id NULL, item_id NULL);
ALTER TABLE news_feedback ADD (event_key VARCHAR2(64 CHAR));
ALTER TABLE news_feedback DROP CONSTRAINT uq_feedback_request;
ALTER TABLE news_feedback DROP CONSTRAINT uq_feedback_item;

-- Oracle also compares composite keys whose non-null components match. Exclude other target kinds entirely.
CREATE UNIQUE INDEX uq_feedback_delivery_request ON news_feedback (
    CASE WHEN event_key IS NULL THEN capability_id END,
    CASE WHEN event_key IS NULL THEN idempotency_key END
);
CREATE UNIQUE INDEX uq_feedback_delivery_item ON news_feedback (
    CASE WHEN event_key IS NULL THEN recipient_id END,
    CASE WHEN event_key IS NULL THEN report_id END,
    CASE WHEN event_key IS NULL THEN item_id END
);
CREATE UNIQUE INDEX uq_feedback_event_request ON news_feedback (
    CASE WHEN event_key IS NOT NULL THEN report_id END,
    CASE WHEN event_key IS NOT NULL THEN idempotency_key END
);
CREATE UNIQUE INDEX uq_feedback_event_item ON news_feedback (
    CASE WHEN event_key IS NOT NULL THEN report_id END,
    CASE WHEN event_key IS NOT NULL THEN event_key END
);
ALTER TABLE news_feedback ADD CONSTRAINT ck_feedback_target CHECK (
    (event_key IS NULL AND capability_id IS NOT NULL AND recipient_id IS NOT NULL AND item_id IS NOT NULL)
    OR (event_key IS NOT NULL AND LENGTH(event_key)=64 AND capability_id IS NULL AND recipient_id IS NULL
        AND item_id IS NULL AND allow_personalization='N')
);

-- Keep every accepted retry key reserved, including an identical event retried with a new key.
CREATE TABLE news_feedback_event_requests (
    report_id NUMBER(19) NOT NULL REFERENCES news_reports(id),
    idempotency_key VARCHAR2(100 CHAR) NOT NULL,
    feedback_id NUMBER(19) NOT NULL REFERENCES news_feedback(id) ON DELETE CASCADE,
    CONSTRAINT pk_feedback_event_request PRIMARY KEY (report_id,idempotency_key)
);

ALTER TABLE news_feedback_jobs MODIFY (recipient_id NULL, topic_id NULL);
ALTER TABLE news_feedback_jobs ADD CONSTRAINT ck_feedback_job_owner CHECK (
    (kind='EVALUATE' AND feedback_id IS NULL AND recipient_id IS NOT NULL AND topic_id IS NOT NULL)
    OR (kind='REVIEW' AND feedback_id IS NOT NULL AND
        ((recipient_id IS NOT NULL AND topic_id IS NOT NULL) OR (recipient_id IS NULL AND topic_id IS NULL)))
);
