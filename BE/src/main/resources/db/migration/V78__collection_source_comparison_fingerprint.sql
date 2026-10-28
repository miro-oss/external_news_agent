-- New collection reservations freeze source settings for equal-condition report comparison.
-- NULL on old runs is intentional: historical settings cannot be inferred from today's source.
ALTER TABLE news_collection_run_items ADD comparison_source_fingerprint VARCHAR2(64 CHAR);
