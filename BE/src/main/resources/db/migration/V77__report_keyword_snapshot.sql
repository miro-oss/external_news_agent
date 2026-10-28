-- Historical candidate universes cannot be reconstructed from current keywords or issue memberships.
ALTER TABLE news_reports ADD report_keyword_snapshot CLOB;
