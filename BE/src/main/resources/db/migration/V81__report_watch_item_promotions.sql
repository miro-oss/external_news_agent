-- Preserve generated content and record only the user's explicit promotion selections.
-- NULL on existing reports means that no watch items have been promoted.
ALTER TABLE news_reports ADD promoted_watch_item_keys CLOB;
ALTER TABLE news_reports ADD CONSTRAINT ck_reports_promoted_watches CHECK (promoted_watch_item_keys IS JSON);
