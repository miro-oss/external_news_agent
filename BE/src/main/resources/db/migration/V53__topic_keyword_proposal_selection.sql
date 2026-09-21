-- Preserve original suggestions while recording the subset chosen at the last approval.
-- NULL keeps pending proposals and legacy approvals compatible without rewriting their history.
ALTER TABLE news_topic_keyword_proposals ADD (
    selected_change_indexes_json CLOB,
    CONSTRAINT ck_keyword_proposal_selection CHECK (selected_change_indexes_json IS JSON)
);
