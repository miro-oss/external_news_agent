-- Keep approval deltas distinct from legacy approvals whose later edits cannot be reconstructed.
ALTER TABLE news_topic_keyword_proposals ADD (
    applied_changes_json CLOB,
    CONSTRAINT ck_keyword_proposal_applied CHECK (applied_changes_json IS JSON)
);

-- Per-keyword revisions protect subsequent manual edits and other approvals during a reversal.
ALTER TABLE news_topics ADD (
    keyword_revisions_json CLOB DEFAULT '{}' NOT NULL,
    CONSTRAINT ck_topic_keyword_revisions CHECK (keyword_revisions_json IS JSON)
);
